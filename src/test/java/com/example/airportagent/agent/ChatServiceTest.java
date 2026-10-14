package com.example.airportagent.agent;

import com.example.airportagent.service.AirportQueryService;
import com.example.airportagent.service.QueryResult;
import com.example.airportagent.service.Views.LongHaulView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the agent with a scripted model. The model chooses the tools like an LLM does. No real LLM is called.
 * The tools, the chat memory, the system prompt and the number check are the real ones.
 */
@SpringBootTest(properties = "spring.ai.google.genai.api-key=test-key-not-real")
class ChatServiceTest {

    @Autowired
    private AirportQueryService service;
    @Autowired
    private JsonMapper json;

    private ScriptedModel model;
    private ChatService chat;

    /** A step of the scripted model: tool calls, or the final text. */
    private record Step(List<AssistantMessage.ToolCall> calls, String text) {
        static Step text(String text) {
            return new Step(List.of(), text);
        }

        static Step calls(AssistantMessage.ToolCall... calls) {
            return new Step(List.of(calls), "");
        }
    }

    private static AssistantMessage.ToolCall call(String id, String tool, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", tool, arguments);
    }

    /** The tool responses at the end of the prompt. The list is empty if the model has not called a tool in this round. */
    private static List<String> toolResponses(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        Message last = messages.get(messages.size() - 1);
        if (last instanceof ToolResponseMessage response) {
            return response.getResponses().stream().map(ToolResponseMessage.ToolResponse::responseData).toList();
        }
        return List.of();
    }

    private static String userText(Prompt prompt) {
        return prompt.getUserMessage().getText();
    }

    private static boolean isRetry(Prompt prompt) {
        return userText(prompt).startsWith("Your last answer");
    }

    private static final class ScriptedModel implements ChatModel {
        Function<Prompt, Step> script = prompt -> Step.text("");
        final List<Prompt> prompts = new ArrayList<>();

        /** The chat client takes its options from the model. A real model gives options that can carry tools. */
        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            Step step = script.apply(prompt);
            AssistantMessage message = step.calls().isEmpty()
                    ? new AssistantMessage(step.text())
                    : AssistantMessage.builder().content("").toolCalls(step.calls()).build();
            return new ChatResponse(List.of(new Generation(message)));
        }
    }

    @BeforeEach
    void setUp() {
        model = new ScriptedModel();
        AgentConfig config = new AgentConfig();
        ChatClient client = config.airportChatClient(ChatClient.builder(model), new AirportTools(service, json),
                config.chatMemory(), new ClassPathResource("prompts/system.txt"));
        chat = new ChatService(client, "test-key-not-real");
    }

    private double anchorageShare() {
        return ((QueryResult.Ok<LongHaulView>) service.longHaul("ANC", 2500)).data().sharePassengerOnly() * 100;
    }

    @Test
    void theToolRunsTheRealEngineAndAnAnswerWithEngineNumbersIsVerified() {
        double share = anchorageShare();
        model.script = prompt -> toolResponses(prompt).isEmpty()
                ? Step.calls(call("1", "getLongHaulShare", "{\"airport\":\"ANC\",\"minMiles\":2500}"))
                : Step.text(String.format("About %.1f%% of the passenger departures from ANC are long-haul.", share));

        ChatReply reply = chat.chat("s1", "What is the percentage of long-haul flights out of Anchorage?");

        assertThat(reply.verified()).as(reply.reply()).isTrue();
        assertThat(reply.toolsUsed()).containsExactly("getLongHaulShare");
        assertThat(reply.reply()).contains(String.format("%.1f%%", share));
        assertThat(model.prompts).hasSize(2);
        assertThat(toolResponses(model.prompts.get(1)).get(0)).contains("sharePassengerOnly");
    }

    @Test
    void anInventedNumberCausesOneRetryAndThenACorrectAnswerIsAccepted() {
        double share = anchorageShare();
        model.script = prompt -> {
            if (isRetry(prompt)) {
                return Step.text(String.format("The share is %.1f%%.", share));
            }
            return toolResponses(prompt).isEmpty()
                    ? Step.calls(call("1", "getLongHaulShare", "{\"airport\":\"ANC\"}"))
                    : Step.text("The share is 99.9%.");
        };

        ChatReply reply = chat.chat("s2", "Long-haul share at Anchorage?");

        assertThat(reply.verified()).isTrue();
        assertThat(reply.reply()).doesNotContain("99.9");
        assertThat(model.prompts.get(model.prompts.size() - 1).getUserMessage().getText()).contains("99.9");
    }

    @Test
    void ifTheRetryAlsoHasAnInventedNumberTheAnswerComesFromTheToolData() {
        model.script = prompt -> {
            if (!isRetry(prompt) && toolResponses(prompt).isEmpty()) {
                return Step.calls(call("1", "getLongHaulShare", "{\"airport\":\"ANC\"}"));
            }
            return Step.text("The share is 99.9%.");
        };

        ChatReply reply = chat.chat("s3", "Long-haul share at Anchorage?");

        assertThat(reply.verified()).isFalse();
        assertThat(reply.unverifiedItems()).containsExactly("99.9");
        assertThat(reply.reply()).contains("getLongHaulShare").contains("sharePassengerOnly").doesNotContain("99.9");
    }

    @Test
    void aFollowUpQuestionSeesTheEarlierMessagesAndMayUseEarlierToolNumbers() {
        double share = anchorageShare();
        model.script = prompt -> {
            if (userText(prompt).contains("Anchorage")) {
                return toolResponses(prompt).isEmpty()
                        ? Step.calls(call("1", "getLongHaulShare", "{\"airport\":\"ANC\"}"))
                        : Step.text(String.format("The share is %.1f%%.", share));
            }
            return Step.text(String.format("As I said, it is %.1f%%.", share));
        };

        chat.chat("s4", "Long-haul share at Anchorage?");
        ChatReply second = chat.chat("s4", "Please repeat the number.");

        assertThat(second.verified()).isTrue();
        assertThat(second.toolsUsed()).isEmpty();
        Prompt lastPrompt = model.prompts.get(model.prompts.size() - 1);
        assertThat(lastPrompt.getInstructions().toString()).contains("Long-haul share at Anchorage?");
    }

    @Test
    void theSessionsDoNotShareMemoryOrToolResults() {
        double share = anchorageShare();
        model.script = prompt -> {
            if (userText(prompt).contains("Anchorage") && toolResponses(prompt).isEmpty()) {
                return Step.calls(call("1", "getLongHaulShare", "{\"airport\":\"ANC\"}"));
            }
            return Step.text(String.format("The share is %.1f%%.", share));
        };

        chat.chat("one", "Long-haul share at Anchorage?");
        ChatReply other = chat.chat("two", "Please repeat the number.");

        assertThat(other.verified()).isFalse();
        assertThat(model.prompts.get(model.prompts.size() - 1).getInstructions().toString()).doesNotContain("Anchorage");
    }

    @Test
    void theNumberOfToolCallsInOneTurnIsLimited() {
        List<AssistantMessage.ToolCall> many = new ArrayList<>();
        for (int i = 0; i < ToolLog.MAX_CALLS_PER_TURN + 2; i++) {
            many.add(call("id" + i, "getMethodology", "{}"));
        }
        model.script = prompt -> toolResponses(prompt).isEmpty() ? new Step(many, "") : Step.text("Done.");

        ChatReply reply = chat.chat("s6", "How does the score work?");

        assertThat(reply.toolsUsed()).hasSize(ToolLog.MAX_CALLS_PER_TURN);
        List<String> responses = toolResponses(model.prompts.get(1));
        assertThat(responses).hasSize(ToolLog.MAX_CALLS_PER_TURN + 2);
        assertThat(responses.get(responses.size() - 1)).contains("limit of 5 tool calls");
    }

    @Test
    void anOutOfScopeResultReachesTheModelWithItsReason() {
        model.script = prompt -> toolResponses(prompt).isEmpty()
                ? Step.calls(call("1", "getLongHaulShare", "{\"airport\":\"LHR\"}"))
                : Step.text("That airport is not in the data.");

        ChatReply reply = chat.chat("s7", "Long-haul share at Heathrow?");

        assertThat(toolResponses(model.prompts.get(1)).get(0)).contains("OUT_OF_SCOPE").contains("LHR");
        assertThat(reply.verified()).isTrue();
    }

    @Test
    void aFailureOfTheModelGivesAClearMessage() {
        model.script = prompt -> {
            throw new IllegalStateException("429 quota exceeded");
        };

        ChatReply reply = chat.chat("s8", "Hello");

        assertThat(reply.verified()).isFalse();
        assertThat(reply.reply()).contains("not available").contains("429").contains("/api");
    }

    @Test
    void theSystemPromptIsSentWithEachRequest() {
        model.script = prompt -> Step.text("Hello.");

        chat.chat("s9", "Hello");

        assertThat(model.prompts.get(0).getSystemMessage().getText()).contains("Use only numbers that are in the tool results");
    }

    @Test
    void theModelCanCallEachOfTheSevenTools() {
        String[][] specs = {
                {"rankAirports", "{\"region\":\"NEW_ENGLAND\",\"profile\":\"terminal\"}"},
                {"compareAirports", "{\"codes\":[\"LA\",\"Santa Ana\"],\"focus\":\"congestion\"}"},
                {"getAirportProfile", "{\"airport\":\"SFO\"}"},
                {"getLongHaulShare", "{\"airport\":\"Anchorage\"}"},
                {"getUnmetDemandIndex", "{\"airport\":\"SFO\"}"},
                {"getLiveStatus", "{\"airport\":\"LAX\"}"},
                {"getMethodology", "{}"}};
        for (String[] spec : specs) {
            model.prompts.clear();
            model.script = prompt -> toolResponses(prompt).isEmpty()
                    ? Step.calls(call("1", spec[0], spec[1]))
                    : Step.text("Done.");

            chat.chat("tool-" + spec[0], "Question for " + spec[0]);

            assertThat(toolResponses(model.prompts.get(1)).get(0)).as(spec[0]).contains("status");
        }
    }
}

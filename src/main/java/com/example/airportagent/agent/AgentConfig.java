package com.example.airportagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/** The beans of the agent: the chat client with the system prompt, the tools and the chat memory. */
@Configuration
public class AgentConfig {

    /** The chat memory keeps the last messages of each session. A restart clears it. */
    static final int MEMORY_MESSAGES = 20;

    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(MEMORY_MESSAGES)
                .build();
    }

    @Bean
    ChatClient airportChatClient(ChatClient.Builder builder, AirportTools tools, ChatMemory memory,
                                 @Value("classpath:prompts/system.txt") Resource systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultTools(tools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }
}

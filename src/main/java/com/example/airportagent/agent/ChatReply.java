package com.example.airportagent.agent;

import java.util.List;

/**
 * The answer of the chat.
 *
 * @param sessionId       the session of the chat. The client sends it again in the next question.
 * @param reply           the text of the answer
 * @param verified        true if each number of the answer is in a tool result
 * @param toolsUsed       the tools that the agent called for this question
 * @param unverifiedItems the numbers that the check could not find in the tool results. Empty if verified.
 */
public record ChatReply(String sessionId, String reply, boolean verified, List<String> toolsUsed,
                        List<String> unverifiedItems) {}

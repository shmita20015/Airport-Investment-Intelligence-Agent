package com.example.airportagent.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The record of the tool results of one chat session. The agent uses it to check the numbers of the answer.
 * It also limits the number of tool calls in one turn.
 */
public final class ToolLog {

    /** The key of the log in the tool context of Spring AI. */
    public static final String KEY = "toolLog";
    public static final int MAX_CALLS_PER_TURN = 5;
    private static final int MAX_EARLIER = 30;

    public record Call(String tool, String json) {}

    private final List<Call> turn = new ArrayList<>();
    private final Deque<String> earlier = new ArrayDeque<>();
    private int attempts;

    /** Start a new turn. The results of the last turn stay available for follow-up questions. */
    public synchronized void startTurn() {
        turn.forEach(call -> earlier.addLast(call.json()));
        while (earlier.size() > MAX_EARLIER) {
            earlier.removeFirst();
        }
        turn.clear();
        attempts = 0;
    }

    /** Count a tool call. The result is false when the limit of the turn is reached. */
    public synchronized boolean allowCall() {
        attempts++;
        return attempts <= MAX_CALLS_PER_TURN;
    }

    public synchronized void record(String tool, String json) {
        turn.add(new Call(tool, json));
    }

    public synchronized List<Call> turnCalls() {
        return List.copyOf(turn);
    }

    /** The JSON of all results that the answer may use: this turn and the earlier turns of the session. */
    public synchronized List<String> allJson() {
        List<String> all = new ArrayList<>(earlier);
        turn.forEach(call -> all.add(call.json()));
        return all;
    }
}

package com.example.airportagent.agent;

/** All chat slots are in use. The caller must try again later. */
public class ChatBusyException extends RuntimeException {

    public ChatBusyException() {
        super("The chat is busy. Try again in a few seconds.");
    }
}

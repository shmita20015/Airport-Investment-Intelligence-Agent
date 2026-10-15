package com.example.airportagent.agent;

/** All chat slots are in use. The caller must try again later. */
public class ChatBusyException extends RuntimeException {

    /** Creates the exception with a message that tells the caller to try again. */
    public ChatBusyException() {
        super("The chat is busy. Try again in a few seconds.");
    }
}

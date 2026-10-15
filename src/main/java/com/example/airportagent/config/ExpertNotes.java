package com.example.airportagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Optional;

/**
 * Short notes about an airport that no data file shows. They are facts from general knowledge, with a source text.
 * They are never inputs of a score. A result must label them as expert notes.
 * The notes are in application.yml under airport.notes, with the airport code as the key.
 */
@ConfigurationProperties(prefix = "airport")
public record ExpertNotes(Map<String, Note> notes) {

    /** One note and the source text that says where it comes from. */
    public record Note(String text, String source) {}

    /** The compact constructor replaces a missing section by an empty map. */
    public ExpertNotes {
        notes = notes == null ? Map.of() : Map.copyOf(notes);
    }

    /** The note of an airport, or empty if the airport has none. */
    public Optional<Note> noteOf(String airportCode) {
        return Optional.ofNullable(notes.get(airportCode));
    }
}

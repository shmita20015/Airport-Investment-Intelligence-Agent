package com.example.airportagent.data;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Short notes about an airport that no data file shows. They are facts from general knowledge, with a source text.
 * They are never inputs of a score. A result must label them as expert notes.
 */
public final class ExpertNotes {

    public record Note(String text, String source) {}

    private final Map<String, Note> notes;

    private ExpertNotes(Map<String, Note> notes) {
        this.notes = Map.copyOf(notes);
    }

    public static ExpertNotes load() {
        Map<String, Note> notes = new HashMap<>();
        for (Map<String, String> row : Csv.readResource("data/airport_notes.csv")) {
            notes.put(row.get("iata"), new Note(row.get("note"), row.get("source")));
        }
        return new ExpertNotes(notes);
    }

    public static ExpertNotes of(Map<String, Note> notes) {
        return new ExpertNotes(notes);
    }

    public Optional<Note> noteOf(String airportCode) {
        return Optional.ofNullable(notes.get(airportCode));
    }
}

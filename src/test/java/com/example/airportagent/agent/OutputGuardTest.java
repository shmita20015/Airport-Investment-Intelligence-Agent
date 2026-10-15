package com.example.airportagent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutputGuardTest {

    @Test
    void aLinkThatNoToolResultHasIsRemoved() {
        String result = OutputGuard.neutralize("See https://evil.test/a?x=1 and www.evil.test now.", List.of("{}"));

        assertThat(result).isEqualTo("See [link removed] and [link removed] now.");
    }

    @Test
    void aLinkFromAToolResultIsAllowed() {
        String result = OutputGuard.neutralize("Source: https://nasstatus.faa.gov/api/x",
                List.of("{\"source\":\"https://nasstatus.faa.gov/api/x\"}"));

        assertThat(result).contains("https://nasstatus.faa.gov/api/x");
    }

    @Test
    void anImageIsAlwaysRemoved() {
        String result = OutputGuard.neutralize("![chart](https://nasstatus.faa.gov/api/x)",
                List.of("https://nasstatus.faa.gov/api/x"));

        assertThat(result).isEqualTo(OutputGuard.REMOVED);
    }

    @Test
    void aTextWithoutLinksIsNotChanged() {
        String text = "BOS has a score of 73.6 (HIGH confidence).\n| a | b |";

        assertThat(OutputGuard.neutralize(text, List.of())).isEqualTo(text);
    }
}

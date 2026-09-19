package com.example.mcp.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizerTest {

    @Test
    void masksSensitiveTopLevelFields() {
        String sanitized = Sanitizer.sanitize(
                "{\"user\":\"u1\",\"password\":\"p1\",\"token\":\"t1\",\"apiKey\":\"k1\"}");
        assertThat(sanitized)
                .contains("\"password\":\"***\"")
                .contains("\"token\":\"***\"")
                .contains("\"apiKey\":\"***\"")
                .contains("\"user\":\"u1\"");
    }

    @Test
    void masksNestedAndListFields() {
        String sanitized = Sanitizer.sanitize(
                "{\"a\":{\"secret\":\"s\",\"keep\":1},\"list\":[{\"token\":\"t\"},2],\"authorization\":\"Bearer x\"}");
        assertThat(sanitized)
                .contains("\"secret\":\"***\"")
                .contains("\"token\":\"***\"")
                .contains("\"authorization\":\"***\"")
                .contains("\"keep\":1")
                .contains("2");
    }

    @Test
    void recognizesKeyVariants() {
        String sanitized = Sanitizer.sanitize("{\"API_KEY\":\"k\",\"api-key\":\"k2\",\"Password\":\"p\"}");
        assertThat(sanitized)
                .contains("\"API_KEY\":\"***\"")
                .contains("\"api-key\":\"***\"")
                .contains("\"Password\":\"***\"");
    }

    @Test
    void fallsBackToRegexOnInvalidJson() {
        String raw = "{\"password\":\"abc\",\"broken";
        assertThat(Sanitizer.sanitize(raw)).contains("\"password\":\"***\"");
    }

    @Test
    void handlesNullBlankAndPlainJson() {
        assertThat(Sanitizer.sanitize(null)).isNull();
        assertThat(Sanitizer.sanitize("")).isEmpty();
        assertThat(Sanitizer.sanitize("{\"v\":1}")).isEqualTo("{\"v\":1}");
    }
}

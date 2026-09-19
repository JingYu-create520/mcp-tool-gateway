package com.example.mcp.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolActionTest {

    @Test
    void readGoesThroughWithoutConfirmation() {
        assertThat(ToolAction.READ.requiresConfirmation()).isFalse();
    }

    @Test
    void writeAndDangerousRequireConfirmation() {
        assertThat(ToolAction.WRITE.requiresConfirmation()).isTrue();
        assertThat(ToolAction.DANGEROUS.requiresConfirmation()).isTrue();
    }
}

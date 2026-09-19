package com.example.mcp.core.tool;

import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EchoToolsTest {

    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);
    private final ToolCallAuditor auditor = mock(ToolCallAuditor.class);
    private final AuditEvent auditEvent = new AuditEvent();
    private final EchoTools echoTools = new EchoTools(auditPublisher, auditor);

    @BeforeEach
    void setUp() {
        when(auditor.event(anyString(), anyString())).thenReturn(auditEvent);
    }

    @Test
    void returnsMessageUnchanged() {
        assertThat(echoTools.echo("hello gateway")).isEqualTo("hello gateway");
    }

    @Test
    void handlesEmptyMessage() {
        assertThat(echoTools.echo("")).isEmpty();
    }

    @Test
    void publishesExecutedAudit() {
        echoTools.echo("audit-check");

        verify(auditPublisher).publish(auditEvent);
        assertThat(auditEvent.getStatus()).isEqualTo("EXECUTED");
        assertThat(auditEvent.getOutput()).isEqualTo("audit-check");
        assertThat(auditEvent.getDurationMs()).isNotNull();
    }
}

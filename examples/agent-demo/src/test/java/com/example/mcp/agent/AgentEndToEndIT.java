package com.example.mcp.agent;

import com.example.mcp.McpGatewayApplication;
import com.example.mcp.audit.AuditEventRepository;
import com.example.mcp.audit.OutboxRelay;
import com.example.mcp.tools.vredis.MockVredisServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：同进程启动完整 Gateway（classes 显式指定，避开 agent 自己的启动类），
 * 用 MockVredisServer 充当 vredis 后端，然后让 AgentRunner 以真实 MCP Client
 * 走完 5 步任务，并断言 Gateway 侧的确认状态与审计轨迹。
 *
 * 说明：agent-demo 的 application.yml 会遮蔽 gateway-core 的同名配置文件，
 * 因此 Gateway 所需的关键配置（API Key、MCP server、数据源）在这里显式注入。
 */
@SpringBootTest(classes = McpGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AgentEndToEndIT {

    static final MockVredisServer vredis = new MockVredisServer();

    @BeforeAll
    static void startMock() {
        vredis.start();
    }

    @AfterAll
    static void stopMock() {
        vredis.stop();
    }

    @DynamicPropertySource
    static void gatewayProps(DynamicPropertyRegistry registry) {
        // Gateway 在本测试 JVM 里用 H2 内存库（Flyway 关闭，Hibernate 建表）；
        // API Key 来自 DbApiKeySeeder 启动种子（admin-key 含 admin/user/confirmer 角色）
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:agent_demo_gateway;DB_CLOSE_DELAY=-1");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        // 与 gateway 主配置一致：关 OSIV（无视图层，且避免请求级持久化上下文的实体快照问题）
        registry.add("spring.jpa.open-in-view", () -> "false");
        // MCP server（agent-demo 的 application.yml 遮蔽了 gateway 的同名文件，这里显式补齐）
        registry.add("spring.ai.mcp.server.protocol", () -> "STREAMABLE");
        registry.add("spring.ai.mcp.server.type", () -> "SYNC");
        registry.add("spring.ai.mcp.server.name", () -> "mcp-tool-gateway");
        registry.add("spring.ai.mcp.server.version", () -> "0.1.0");
        // vredis 后端换成 Mock
        registry.add("vredis.base-url", vredis::baseUrl);
    }

    @LocalServerPort
    int port;

    @Autowired
    OutboxRelay relay;

    @Autowired
    AuditEventRepository auditRepository;

    @Test
    void agentCompletesFiveStepWorkflow() {
        AgentRunner runner = new AgentRunner("http://localhost:" + port, "admin-key");

        AgentRunner.DemoResult result = runner.run();

        assertThat(result.searchStatus()).isEqualTo("executed");
        assertThat(result.upsertFirstStatus()).isEqualTo("pending");
        assertThat(result.confirmationId()).isNotBlank();
        assertThat(result.confirmHttpStatus()).isEqualTo("200");
        assertThat(result.upsertSecondStatus()).isEqualTo("executed");
        assertThat(result.auditRows()).isGreaterThanOrEqualTo(4);

        // Gateway 侧：确认状态机与审计轨迹（PENDING → CONFIRMED → EXECUTING → EXECUTED）
        awaitAuditStatus("PENDING");
        awaitAuditStatus("CONFIRMED");
        awaitAuditStatus("EXECUTING");
        awaitAuditStatus("EXECUTED");
    }

    private void awaitAuditStatus(String status) {
        for (int i = 0; i < 10; i++) {
            relay.relayOnce();
            if (!auditRepository
                    .findByToolNameAndStatusOrderByCreatedAtDesc("vredis_upsert", status).isEmpty()) {
                return;
            }
        }
        throw new AssertionError("审计记录未出现: vredis_upsert/" + status);
    }
}

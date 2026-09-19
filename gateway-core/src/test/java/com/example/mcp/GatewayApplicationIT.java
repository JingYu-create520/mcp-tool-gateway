package com.example.mcp;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * H2 兜底集成测试：本机无 Docker 也能跑。
 * GitHub Actions 上另有 GatewayApplicationPostgresIT 验证 PostgreSQL + Flyway 链路。
 * 注：Boot 4 已移除 TestRestTemplate，这里直接用 Spring 的 RestClient。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class GatewayApplicationIT {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    int port;

    @Autowired
    McpSyncServer mcpSyncServer;

    RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("X-API-Key", "admin-key")
                .build();
    }

    @Test
    void contextLoads() {
    }

    @Test
    void adminApiRegistersUpdatesAndDeletesTool() {
        String schema = "{\"type\":\"object\",\"properties\":{\"who\":{\"type\":\"string\"}},\"required\":[\"who\"]}";

        Map<String, Object> create = Map.of(
                "name", "greet",
                "description", "测试注册的问候工具",
                "inputSchema", schema,
                "sideEffect", "WRITE",
                "enabled", true);

        var created = client.post().uri("/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .body(create)
                .retrieve()
                .toEntity(MAP_TYPE);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).extracting("name", "sideEffect").containsExactly("greet", "WRITE");

        var list = client.get().uri("/admin/tools").retrieve().toEntity(LIST_TYPE);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).extracting(tool -> tool.get("name")).contains("greet");

        Map<String, Object> update = Map.of(
                "name", "greet",
                "description", "更新后的问候工具",
                "inputSchema", schema,
                "sideEffect", "READ");
        var updated = client.put().uri("/admin/tools/greet")
                .contentType(MediaType.APPLICATION_JSON)
                .body(update)
                .retrieve()
                .toEntity(MAP_TYPE);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).extracting("description", "sideEffect").containsExactly("更新后的问候工具", "READ");

        client.delete().uri("/admin/tools/greet").retrieve().toBodilessEntity();

        var thrown = catchThrowableOfType(RestClientResponseException.class,
                () -> client.get().uri("/admin/tools/greet").retrieve().toEntity(MAP_TYPE));
        assertThat(thrown).isNotNull();
        assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void dynamicRegistryExposesSeededToolToMcp() {
        var names = mcpSyncServer.listTools().stream()
                .map(McpSchema.Tool::name)
                .collect(Collectors.toSet());
        assertThat(names).contains("echo", "vredis_search");
    }
}

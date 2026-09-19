package com.example.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL 集成测试：验证 V1__init.sql（JSONB）在真实 PG 上可用、工具目录可正常读写。
 * 本机无 Docker 时自动跳过（disabledWithoutDocker）；GitHub Actions（ubuntu-latest 自带 Docker）必跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class GatewayApplicationPostgresIT {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    int port;

    RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("X-API-Key", "admin-key")
                .build();
    }

    @Test
    void flywaySchemaAcceptsToolRegistryWrites() {
        Map<String, Object> create = Map.of(
                "name", "pg_probe",
                "description", "PostgreSQL 链路探针",
                "inputSchema", "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"}},\"required\":[\"pattern\"]}",
                "sideEffect", "READ",
                "enabled", true);

        var created = client.post().uri("/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .body(create)
                .retrieve()
                .toEntity(MAP_TYPE);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).extracting("name").isEqualTo("pg_probe");

        var list = client.get().uri("/admin/tools").retrieve().toEntity(LIST_TYPE);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody())
                .extracting(tool -> tool.get("name"))
                .contains("pg_probe", "vredis_search");
    }
}

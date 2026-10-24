package com.example.mcp.upstream;

import com.example.mcp.security.ToolAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * upstream 工具安全分级：名字启发式必须把有副作用的工具判为 WRITE/DANGEROUS
 * （进入人工确认 + admin 角色），只有明确的只读语义才直通。
 */
class UpstreamPermissionMapperTest {

    private final UpstreamPermissionMapper mapper = new UpstreamPermissionMapper();

    @Test
    void destructiveNamesMapToDangerous() {
        assertThat(mapper.sideEffect("vredis_delete")).isEqualTo(ToolAction.DANGEROUS);
        assertThat(mapper.sideEffect("cache_remove")).isEqualTo(ToolAction.DANGEROUS);
        assertThat(mapper.sideEffect("purge_all")).isEqualTo(ToolAction.DANGEROUS);
        assertThat(mapper.sideEffect("drop_table")).isEqualTo(ToolAction.DANGEROUS);
    }

    @Test
    void writingNamesMapToWrite() {
        assertThat(mapper.sideEffect("vredis_upsert")).isEqualTo(ToolAction.WRITE);
        assertThat(mapper.sideEffect("deploy_service")).isEqualTo(ToolAction.WRITE);
        assertThat(mapper.sideEffect("flush_cache")).isEqualTo(ToolAction.WRITE);
        assertThat(mapper.sideEffect("create_user")).isEqualTo(ToolAction.WRITE);
        assertThat(mapper.sideEffect("update_config")).isEqualTo(ToolAction.WRITE);
    }

    @Test
    void readNamesStayRead() {
        assertThat(mapper.sideEffect("vredis_search")).isEqualTo(ToolAction.READ);
        assertThat(mapper.sideEffect("get_stats")).isEqualTo(ToolAction.READ);
        assertThat(mapper.sideEffect("now")).isEqualTo(ToolAction.READ);
        assertThat(mapper.sideEffect(null)).isEqualTo(ToolAction.READ);
    }

    @Test
    void privilegedToolsRequireAdminRole() {
        UpstreamProperties.Upstream upstream = new UpstreamProperties.Upstream(
                "time", "http://localhost:8090/mcp", "", true, "user");
        assertThat(mapper.requiredRole(upstream, "deploy_service")).isEqualTo("admin");
        // DANGEROUS 同样提权（此前只断言了 WRITE 的提权）
        assertThat(mapper.requiredRole(upstream, "vredis_delete")).isEqualTo("admin");
        assertThat(mapper.requiredRole(upstream, "now")).isEqualTo("user");
    }
}

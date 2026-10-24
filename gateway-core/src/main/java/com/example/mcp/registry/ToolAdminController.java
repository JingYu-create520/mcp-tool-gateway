package com.example.mcp.registry;

import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventPublisher;
import com.example.mcp.core.tool.ToolCallAuditor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 工具目录管理端（URL 层 hasRole(admin)，见 SecurityConfig）。
 * 写操作（注册/更新/删除）会改变 AI 可用的能力面，全部落审计
 * （TOOL_REGISTERED / TOOL_UPDATED / TOOL_DELETED）——事后可追责"谁在何时改了工具目录"。
 */
@Tag(name = "工具目录", description = "工具的注册/更新/下线（实时联动 MCP Server）")
@RestController
@RequestMapping("/admin/tools")
public class ToolAdminController {

    private final ToolRegistryService service;
    private final AuditEventPublisher auditPublisher;
    private final ToolCallAuditor auditor;

    public ToolAdminController(ToolRegistryService service, AuditEventPublisher auditPublisher,
                               ToolCallAuditor auditor) {
        this.service = service;
        this.auditPublisher = auditPublisher;
        this.auditor = auditor;
    }

    @PostMapping
    @Operation(summary = "注册新工具（inputSchema 必须是合法 JSON 对象）")
    public ResponseEntity<ToolRegistryResponse> register(@RequestBody ToolRegistryRequest request) {
        ToolRegistry saved = service.register(request);
        audit("TOOL_REGISTERED", request.name(), request.toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(ToolRegistryResponse.from(saved));
    }

    @GetMapping
    @Operation(summary = "工具目录列表")
    public List<ToolRegistryResponse> listAll() {
        return service.listAll().stream().map(ToolRegistryResponse::from).toList();
    }

    @GetMapping("/{name}")
    public ToolRegistryResponse get(@PathVariable String name) {
        return service.findByName(name).map(ToolRegistryResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在: " + name));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新工具定义")
    public ToolRegistryResponse update(@PathVariable String name, @RequestBody ToolRegistryRequest request) {
        ToolRegistryResponse updated = service.update(name, request).map(ToolRegistryResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在: " + name));
        audit("TOOL_UPDATED", name, request.toString());
        return updated;
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) {
        if (!service.delete(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在: " + name);
        }
        audit("TOOL_DELETED", name, null);
    }

    private void audit(String action, String toolName, String detail) {
        AuditEvent event = auditor.event(toolName, detail);
        event.setStatus(action);
        auditPublisher.publish(event);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}

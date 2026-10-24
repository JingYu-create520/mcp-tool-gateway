package com.example.mcp.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审计查询端点（需 admin 角色，/admin/** URL 层已保护）。
 * 动态条件：callerId / toolName / status / from / to，按 createdAt 倒序分页。
 */
@Tag(name = "审计查询", description = "工具调用轨迹的动态过滤与分页（证据链只读）")
@RestController
@RequestMapping("/admin/audit")
public class AuditController {

    private final AuditEventRepository auditRepository;

    public AuditController(AuditEventRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Operation(summary = "审计查询", description = "callerId/toolName/status/confirmationId/from/to 组合过滤，按创建时间倒序分页")
    @GetMapping
    public Page<AuditEvent> query(
            @RequestParam(required = false) String callerId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String confirmationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<Specification<AuditEvent>> specs = new ArrayList<>();
        if (notBlank(callerId)) {
            specs.add((root, query, cb) -> cb.equal(root.get("callerId"), callerId));
        }
        if (notBlank(toolName)) {
            specs.add((root, query, cb) -> cb.equal(root.get("toolName"), toolName));
        }
        if (notBlank(status)) {
            specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (notBlank(confirmationId)) {
            specs.add((root, query, cb) -> cb.equal(root.get("confirmationId"), confirmationId));
        }
        if (from != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
        }
        if (to != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.<Instant>get("createdAt"), to));
        }

        PageRequest pageRequest =
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"));
        return auditRepository.findAll(Specification.allOf(specs), pageRequest);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** page=-1、size 越界等参数问题返回 400（PageRequest 抛 IllegalArgumentException）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        // 与其他控制器的错误 shape 保持一致：error + message 两字段
        return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "message", String.valueOf(e.getMessage())));
    }
}

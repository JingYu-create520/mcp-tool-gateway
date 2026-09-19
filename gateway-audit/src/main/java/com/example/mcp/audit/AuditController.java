package com.example.mcp.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计查询端点（需 admin 角色，/admin/** URL 层已保护）。
 * 动态条件：callerId / toolName / status / from / to，按 createdAt 倒序分页。
 */
@RestController
@RequestMapping("/admin/audit")
public class AuditController {

    private final AuditEventRepository auditRepository;

    public AuditController(AuditEventRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @GetMapping
    public Page<AuditEvent> query(
            @RequestParam(required = false) String callerId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String status,
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
}

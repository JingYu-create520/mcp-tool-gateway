package com.example.mcp.registry;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 工具目录管理端。阶段一暂未接入认证（阶段二上 API Key），
 * 部署时该端点必须置于内网或反向代理之后。
 */
@RestController
@RequestMapping("/admin/tools")
public class ToolAdminController {

    private final ToolRegistryService service;

    public ToolAdminController(ToolRegistryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ToolRegistryResponse> register(@RequestBody ToolRegistryRequest request) {
        ToolRegistry saved = service.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ToolRegistryResponse.from(saved));
    }

    @GetMapping
    public List<ToolRegistryResponse> listAll() {
        return service.listAll().stream().map(ToolRegistryResponse::from).toList();
    }

    @GetMapping("/{name}")
    public ToolRegistryResponse get(@PathVariable String name) {
        return service.findByName(name).map(ToolRegistryResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在: " + name));
    }

    @PutMapping("/{name}")
    public ToolRegistryResponse update(@PathVariable String name, @RequestBody ToolRegistryRequest request) {
        return service.update(name, request).map(ToolRegistryResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在: " + name));
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) {
        if (!service.delete(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在: " + name);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}

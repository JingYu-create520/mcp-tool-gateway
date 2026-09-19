package com.example.mcp.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToolRegistryRepository extends JpaRepository<ToolRegistry, UUID> {

    Optional<ToolRegistry> findByName(String name);

    List<ToolRegistry> findByEnabledTrue();
}

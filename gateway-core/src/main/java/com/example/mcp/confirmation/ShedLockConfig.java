package com.example.mcp.confirmation;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ShedLock 分布式调度互斥：多实例部署时保证同一时刻只有一个实例执行过期扫描。
 * usingDbTime 使用数据库时钟判定锁有效期，避免实例间时钟漂移。
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .usingDbTime()
                        .build());
    }

    /**
     * shedlock 表兜底初始化：PostgreSQL 由 V2 迁移建表（IF NOT EXISTS 幂等，两者不冲突）；
     * H2 测试/本地 profile 关闭 Flyway，由这里保证表存在，调度器才能正常取锁。
     */
    @Bean
    InitializingBean shedLockTableInitializer(JdbcTemplate jdbcTemplate) {
        return () -> jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS shedlock (" +
                        "name VARCHAR(64) PRIMARY KEY, " +
                        "lock_until TIMESTAMP WITH TIME ZONE NOT NULL, " +
                        "locked_at TIMESTAMP WITH TIME ZONE NOT NULL, " +
                        "locked_by VARCHAR(255) NOT NULL)");
    }
}

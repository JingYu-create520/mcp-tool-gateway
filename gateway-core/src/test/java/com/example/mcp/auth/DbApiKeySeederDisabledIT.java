package com.example.mcp.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 种子开关的行为锚定：gateway.seed-api-keys=false 时 seeder bean 不存在。
 * 若 ConditionalOnProperty 的 havingValue/matchIfMissing 写错，开关会静默失效
 * （该关的关不掉），此类错误没有运行时报错，只能靠这个测试抓住。
 */
@SpringBootTest(properties = "gateway.seed-api-keys=false")
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:seeder_disabled;DB_CLOSE_DELAY=-1")
class DbApiKeySeederDisabledIT {

    @Autowired
    ApplicationContext context;

    @Test
    void seederBeanIsAbsentWhenDisabled() {
        assertThat(context.getBeansOfType(DbApiKeySeeder.class)).isEmpty();
    }
}

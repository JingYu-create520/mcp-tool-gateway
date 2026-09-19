package com.example.mcp.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * Demo agent 启动类。启动即用 MCP Client 连接 Gateway 跑完 5 步任务，然后退出。
 * 注意：本类位于 gateway 组件扫描范围（com.example.mcp.**）之内，
 * 因此 demo 的 CommandLineRunner 用 @Profile("!test") 门控——
 * AgentEndToEndIT 以 test profile 启动 Gateway 上下文时不会重复执行 demo。
 */
@SpringBootApplication
@EnableConfigurationProperties(AgentConfig.class)
public class AgentDemoApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(AgentDemoApplication.class, args);
        System.exit(SpringApplication.exit(context));
    }

    @Bean
    @Profile("!test")
    CommandLineRunner demoRunner(AgentConfig config) {
        return args -> {
            log(config);
            AgentRunner.DemoResult result = new AgentRunner(config.baseUrl(), config.apiKey()).run();
            log(result);
        };
    }

    private static void log(Object value) {
        var logger = org.slf4j.LoggerFactory.getLogger("AGENT-DEMO");
        logger.info("{}", value);
    }
}

package com.example.mcp.confirmation;

import com.example.mcp.registry.ToolRegistryRequest;
import com.example.mcp.registry.ToolRegistryService;
import com.example.mcp.security.CallerContext;
import com.example.mcp.security.ToolAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原子 claim 的真并发验证：N 个线程同时执行同一条已确认输入，
 * 数据库条件 UPDATE（claimForExecution）必须保证恰好一个 executed、其余落回新 PENDING。
 * 回归背景：此前所有测试都是"手动置 EXECUTING 再顺序调第二次"的模拟，
 * 从未真正并发地行使过 claim 的原子性——这是"多实例只执行一次"承诺的终极锚点。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentClaimIT {

    private static final String TOOL = "concurrent_claim_probe";

    @Autowired
    SafeToolExecutor executor;

    @Autowired
    ToolRegistryService toolRegistryService;

    @Autowired
    ConfirmationService confirmationService;

    @Autowired
    PendingExecutionRepository pendingRepository;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        if (toolRegistryService.findByName(TOOL).isEmpty()) {
            toolRegistryService.register(new ToolRegistryRequest(
                    TOOL,
                    "并发 claim 探针（无 rateLimit，避免限流干扰并发结果）",
                    "{\"type\":\"object\",\"properties\":{\"k\":{\"type\":\"string\"}},\"required\":[\"k\"]}",
                    ToolAction.WRITE,
                    "admin",
                    null,
                    null,
                    true));
        }
        authenticate();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exactlyOneExecutionUnderTrueConcurrency() throws Exception {
        // 执行器计数：真实执行必须恰好发生 1 次
        AtomicInteger executions = new AtomicInteger();
        String input = "{\"k\":\"race-" + UUID.randomUUID() + "\"}";

        Map<String, Object> first = executor.execute(TOOL, input, exec -> {
            executions.incrementAndGet();
            return "{\"ok\":true}";
        });
        assertThat(first).containsEntry("status", "pending");
        UUID confirmationId = UUID.fromString((String) first.get("confirmationId"));
        confirmationService.confirm(confirmationId);

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        ConcurrentHashMap<String, Integer> outcomes = new ConcurrentHashMap<>();
        try {
            Callable<Map<String, Object>> racer = () -> {
                // SecurityContext 是 ThreadLocal：并发线程各自写入身份
                authenticate();
                startGate.await();
                return executor.execute(TOOL, input, exec -> {
                    executions.incrementAndGet();
                    sleepQuietly(50); // 拉宽执行窗口，放大 claim 竞争
                    return "{\"ok\":true}";
                });
            };
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(racer));
            }
            startGate.countDown(); // 全部线程同时起跑
            for (Future<Map<String, Object>> future : futures) {
                Map<String, Object> outcome = future.get(30, TimeUnit.SECONDS);
                outcomes.merge(String.valueOf(outcome.get("status")), 1, Integer::sum);
            }
        } finally {
            pool.shutdownNow();
        }

        // 恰好一个 executed；其余全部落回 pending（claim 抢不到的语义）
        assertThat(outcomes.get("executed")).isEqualTo(1);
        assertThat(outcomes.get("pending")).isEqualTo(threads - 1);
        // 真实执行只发生过一次（第一次 pending 创建不算，confirm 后的并发里只执行 1 次）
        assertThat(executions.get()).isEqualTo(1);
        // 原确认单终态 EXECUTED
        assertThat(pendingRepository.findByConfirmationId(confirmationId).orElseThrow().getStatus())
                .isEqualTo(ConfirmationStatus.EXECUTED);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void authenticate() {
        CallerContext caller = new CallerContext("admin-user", "tenant-a", Set.of("admin", "user"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null, List.of()));
    }
}

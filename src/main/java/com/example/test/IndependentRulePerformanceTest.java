package com.example.test;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(2)
public class IndependentRulePerformanceTest {

    @Param({"100", "500", "1000", "2000", "5000"})
    private int ruleCount;

    private List<String> ipAddresses;
    private IndependentRuleStrategy strategy;
    private MetricsCollector metricsCollector;

    // 独立规则策略实现
    public static class IndependentRuleStrategy {
        private Map<String, String> ipToResourceMap = new ConcurrentHashMap<>();

        public void initRules(List<String> ips, int qpsLimit) {
            List<FlowRule> rules = new ArrayList<>();

            for (String ip : ips) {
                String resource = "ip_rule_" + ip.replace(".", "_");
                ipToResourceMap.put(ip, resource);

                FlowRule rule = new FlowRule();
                rule.setResource(resource);
                rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
                rule.setCount(qpsLimit);
                rule.setLimitApp("default");
                rules.add(rule);
            }

            FlowRuleManager.loadRules(rules);
        }

        public boolean allowRequest(String ip) {
            String resource = ipToResourceMap.get(ip);
            if (resource == null) {
                return false;
            }

            Entry entry = null;
            try {
                entry = SphU.entry(resource);
                return true;
            } catch (BlockException e) {
                return false;
            } finally {
                if (entry != null) {
                    entry.exit();
                }
            }
        }
    }

    // 指标收集器
    public static class MetricsCollector {
        private static final AtomicLong totalRequests = new AtomicLong();
        private static final AtomicLong passedRequests = new AtomicLong();
        private static final AtomicLong blockedRequests = new AtomicLong();
        private static final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        public static void record(long latency, boolean passed) {
            totalRequests.incrementAndGet();
            if (passed) {
                passedRequests.incrementAndGet();
            } else {
                blockedRequests.incrementAndGet();
            }
            latencies.add(latency);
        }

        public static void reset() {
            totalRequests.set(0);
            passedRequests.set(0);
            blockedRequests.set(0);
            latencies.clear();
        }

        public static void printMetrics(int ruleCount) {
            if (totalRequests.get() == 0) {
                return;
            }

            System.out.println("\n" + new String(new char[60]).replace("\0", "="));

            System.out.println("性能指标统计 (规则数: " + ruleCount + ")");
            System.out.println("\n" + new String(new char[60]).replace("\0", "="));

            long total = totalRequests.get();
            long passed = passedRequests.get();
            long blocked = blockedRequests.get();

            System.out.printf("总请求数: %,d\n", total);
            System.out.printf("通过请求: %,d (%.2f%%)\n",
                    passed, passed * 100.0 / total);
            System.out.printf("限流请求: %,d (%.2f%%)\n",
                    blocked, blocked * 100.0 / total);

            if (!latencies.isEmpty()) {
                Collections.sort(latencies);
                int size = latencies.size();
                long p50 = latencies.get(size / 2);
                long p90 = latencies.get((int) (size * 0.9));
                long p95 = latencies.get((int) (size * 0.95));
                long p99 = latencies.get((int) (size * 0.99));

                System.out.printf("\n延迟统计 (纳秒):\n");
                System.out.printf("  P50: %,d ns (%.2f ms)\n", p50, p50 / 1_000_000.0);
                System.out.printf("  P90: %,d ns (%.2f ms)\n", p90, p90 / 1_000_000.0);
                System.out.printf("  P95: %,d ns (%.2f ms)\n", p95, p95 / 1_000_000.0);
                System.out.printf("  P99: %,d ns (%.2f ms)\n", p99, p99 / 1_000_000.0);

                // 计算平均值
                double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
                System.out.printf("  平均: %.0f ns (%.2f ms)\n", avg, avg / 1_000_000.0);
            }
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        // 重置指标收集器
        MetricsCollector.reset();

        // 生成IP地址
        ipAddresses = generateIpAddresses(ruleCount);

        // 初始化独立规则
        strategy = new IndependentRuleStrategy();
        long startTime = System.currentTimeMillis();
        strategy.initRules(ipAddresses, 100);
        long loadTime = System.currentTimeMillis() - startTime;

        System.out.printf("\n独立规则方案初始化完成: 规则数=%d, 耗时=%dms%n",
                ruleCount, loadTime);

        // 预热
        warmUp();
    }

    @Benchmark
    @Threads(100)
    public void testIndependentRule(Blackhole blackhole) {
        String ip = ipAddresses.get(
                ThreadLocalRandom.current().nextInt(ruleCount));

        long start = System.nanoTime();
        boolean allowed = strategy.allowRequest(ip);
        long latency = System.nanoTime() - start;

        if (allowed) {
            // 模拟业务处理
            blackhole.consume(processBusiness());
        } else {
            blackhole.consume(fallback());
        }

        MetricsCollector.record(latency, allowed);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // 打印性能指标
        MetricsCollector.printMetrics(ruleCount);

        // 统计ClusterNode数量
        int nodeCount = ClusterBuilderSlot.getClusterNodeMap().size();
        System.out.printf("ClusterNode数量: %d (限制: 6000)%n", nodeCount);

        // 内存使用情况
        Runtime runtime = Runtime.getRuntime();
        runtime.gc(); // 建议GC
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        System.out.printf("内存使用: %.2fMB / %.2fMB (%.1f%%)%n",
                usedMemory / 1024.0 / 1024.0,
                maxMemory / 1024.0 / 1024.0,
                usedMemory * 100.0 / maxMemory);

        // 清理规则
        FlowRuleManager.loadRules(new ArrayList<>());
    }

    // 辅助方法
    private List<String> generateIpAddresses(int count) {
        List<String> ips = new ArrayList<>(count);
        Random random = new Random(42); // 固定种子保证可重复

        for (int i = 0; i < count; i++) {
            String ip = String.format("192.168.%d.%d",
                    random.nextInt(255), random.nextInt(255));
            ips.add(ip);
        }
        return ips;
    }

    private void warmUp() {
        System.out.println("开始预热...");
        // 预热10000次请求
        for (int i = 0; i < 10000; i++) {
            String ip = ipAddresses.get(i % ruleCount);
            try {
                strategy.allowRequest(ip);
            } catch (Exception e) {
                // 忽略预热异常
            }
        }
        System.out.println("预热完成");

        // 重置收集器
        MetricsCollector.reset();
    }

    private Object processBusiness() {
        // 模拟业务处理，耗时1-5ms，调整为固定10ms
        long processTime = 10_000_000 ;// + ThreadLocalRandom.current().nextLong(4_000_000);
        long start = System.nanoTime();
        while (System.nanoTime() - start < processTime) {
            // 忙等待模拟处理
        }
        return "BusinessResult";
    }

    private Object fallback() {
        return "FallbackResult";
    }
}

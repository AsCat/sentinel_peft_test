package com.example.test;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowItem;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
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
@Warmup(iterations = 5, time = 10)
@Measurement(iterations = 10, time = 10)
@Fork(3)
@Threads(100)
public class ComparisonPerformanceTest {

    @Param({"100", "1000", "5000"})
    private int ipCount;

    @Param({"INDEPENDENT", "HOTSPOT"})
    private String strategyType;

    private List<String> ipAddresses;
    private RateLimitStrategy strategy;

    // 策略接口
    interface RateLimitStrategy {
        void initRules(List<String> ips, int defaultQps);
        boolean allowRequest(String ip);
        void updateRule(String ip, int newQps);
        int getRuleCount();
    }

    // 独立规则策略
    public static class IndependentRuleStrategy implements RateLimitStrategy {
        private final Map<String, String> ipToResourceMap = new ConcurrentHashMap<>();

        public void initRules(List<String> ips, int defaultQps) {
            List<FlowRule> rules = new ArrayList<>();

            for (String ip : ips) {
                String resource = "independent_ip_" + ip.replace(".", "_");
                ipToResourceMap.put(ip, resource);

                FlowRule rule = new FlowRule();
                rule.setResource(resource);
                rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
                rule.setCount(defaultQps);
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

        public void updateRule(String ip, int newQps) {
            // 独立规则更新需要重建整个规则列表
            String resource = ipToResourceMap.get(ip);
            if (resource == null) {
                return;
            }

            List<FlowRule> rules = FlowRuleManager.getRules();
            for (FlowRule rule : rules) {
                if (rule.getResource().equals(resource)) {
                    rule.setCount(newQps);
                    break;
                }
            }
            FlowRuleManager.loadRules(rules);
        }

        public int getRuleCount() {
            return FlowRuleManager.getRules().size();
        }
    }

    // 热点参数策略
    public static class HotspotParamStrategy implements RateLimitStrategy {
        private static final String RESOURCE_NAME = "comparison_hotspot_resource";
        private final Map<String, Integer> ipRateCache = new ConcurrentHashMap<>();
        private List<ParamFlowItem> hotspotParams = new ArrayList<>();

        public void initRules(List<String> ips, int defaultQps) {
            // 为10%的IP设置特殊的QPS限制
            Random random = new Random(42);
            int hotspotCount = (int) (ips.size() * 0.1);

            hotspotParams = new ArrayList<>();

            for (int i = 0; i < ips.size(); i++) {
                String ip = ips.get(i);
                int qps = defaultQps;

                if (i < hotspotCount) {
                    qps = 50 + random.nextInt(150); // 50-200
                    ParamFlowItem item = new ParamFlowItem();
                    item.setObject(ip);
                    item.setClassType(String.class.getTypeName());
                    item.setCount(qps);
                    hotspotParams.add(item);
                }

                ipRateCache.put(ip, qps);
            }

            ParamFlowRule rule = new ParamFlowRule(RESOURCE_NAME)
                    .setParamIdx(0)
                    .setGrade(RuleConstant.FLOW_GRADE_QPS)
                    .setCount(defaultQps)
                    .setParamFlowItemList(hotspotParams);

            ParamFlowRuleManager.loadRules(Collections.singletonList(rule));
        }

        public boolean allowRequest(String ip) {
            Entry entry = null;
            try {
                entry = SphU.entry(RESOURCE_NAME,
                        com.alibaba.csp.sentinel.EntryType.IN,
                        1, ip);
                return true;
            } catch (BlockException e) {
                return false;
            } finally {
                if (entry != null) {
                    entry.exit(1, ip);
                }
            }
        }

        public void updateRule(String ip, int newQps) {
            ipRateCache.put(ip, newQps);

            List<ParamFlowRule> rules = ParamFlowRuleManager.getRules();
            if (!rules.isEmpty()) {
                ParamFlowRule oldRule = rules.get(0);
                List<ParamFlowItem> newParams = new ArrayList<>();

                // 复制原有的热点参数，排除要更新的IP
                for (ParamFlowItem item : hotspotParams) {
                    if (!item.getObject().equals(ip)) {
                        newParams.add(item);
                    }
                }

                // 如果需要添加为热点参数
                if (newQps != oldRule.getCount()) {
                    ParamFlowItem newItem = new ParamFlowItem();
                    newItem.setObject(ip);
                    newItem.setClassType(String.class.getTypeName());
                    newItem.setCount(newQps);
                    newParams.add(newItem);
                }

                hotspotParams = newParams;

                ParamFlowRule newRule = new ParamFlowRule(RESOURCE_NAME)
                        .setParamIdx(0)
                        .setGrade(RuleConstant.FLOW_GRADE_QPS)
                        .setCount(oldRule.getCount())
                        .setParamFlowItemList(newParams);

                ParamFlowRuleManager.loadRules(Collections.singletonList(newRule));
            }
        }

        public int getRuleCount() {
            return 1; // 热点参数方案只有一个规则
        }

        public int getHotspotParamCount() {
            return hotspotParams.size();
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

        public static void printMetrics(String strategyType, int ipCount, int ruleCount) {
            if (totalRequests.get() == 0) {
                return;
            }

            System.out.println("\n" + "=".repeat(60));
            System.out.printf("%s策略性能统计 (IP数: %d, 规则数: %d)%n", strategyType, ipCount, ruleCount);
            System.out.println("=".repeat(60));

            long total = totalRequests.get();
            long passed = passedRequests.get();
            long blocked = blockedRequests.get();

            System.out.printf("总请求数: %,d%n", total);
            System.out.printf("通过请求: %,d (%.2f%%)%n",
                    passed, passed * 100.0 / total);
            System.out.printf("限流请求: %,d (%.2f%%)%n",
                    blocked, blocked * 100.0 / total);

            if (!latencies.isEmpty()) {
                List<Long> sorted = new ArrayList<>(latencies);
                Collections.sort(sorted);
                int size = sorted.size();

                long p50 = sorted.get(size / 2);
                long p90 = sorted.get((int) (size * 0.9));
                long p95 = sorted.get((int) (size * 0.95));
                long p99 = sorted.get((int) (size * 0.99));

                long sum = 0;
                for (Long latency : sorted) {
                    sum += latency;
                }
                double avg = (double) sum / size;

                System.out.printf("%n延迟统计:%n");
                System.out.printf("  平均: %.0f ns (%.2f ms)%n", avg, avg / 1_000_000.0);
                System.out.printf("  P50: %,d ns (%.2f ms)%n", p50, p50 / 1_000_000.0);
                System.out.printf("  P90: %,d ns (%.2f ms)%n", p90, p90 / 1_000_000.0);
                System.out.printf("  P95: %,d ns (%.2f ms)%n", p95, p95 / 1_000_000.0);
                System.out.printf("  P99: %,d ns (%.2f ms)%n", p99, p99 / 1_000_000.0);
            }
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        MetricsCollector.reset();

        // 生成IP地址
        ipAddresses = new ArrayList<>(ipCount);
        Random random = new Random(42);

        for (int i = 0; i < ipCount; i++) {
            String ip = String.format("192.168.%d.%d",
                    (i / 256) % 256, i % 256);
            ipAddresses.add(ip);
        }

        // 初始化策略
        if ("INDEPENDENT".equals(strategyType)) {
            strategy = new IndependentRuleStrategy();
        } else {
            strategy = new HotspotParamStrategy();
        }

        long startTime = System.currentTimeMillis();
        strategy.initRules(ipAddresses, 100);
        long loadTime = System.currentTimeMillis() - startTime;

        System.out.printf("%n%s策略初始化完成: IP数=%d, 耗时=%dms%n",
                strategyType, ipCount, loadTime);

        warmUp();
    }

    @Benchmark
    @Threads(100)
    public void testRateLimit(Blackhole blackhole) {
        String ip = ipAddresses.get(ThreadLocalRandom.current().nextInt(ipCount));

        long start = System.nanoTime();
        boolean allowed = strategy.allowRequest(ip);
        long latency = System.nanoTime() - start;

        if (allowed) {
            blackhole.consume(processBusiness());
        } else {
            blackhole.consume(fallback());
        }

        MetricsCollector.record(latency, allowed);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        int ruleCount = strategy.getRuleCount();
        MetricsCollector.printMetrics(strategyType, ipCount, ruleCount);

        // 如果是热点参数策略，显示热点参数数量
        if (strategy instanceof HotspotParamStrategy) {
            int hotspotParamCount = ((HotspotParamStrategy) strategy).getHotspotParamCount();
            System.out.printf("热点参数数量: %d%n", hotspotParamCount);
        }

        // 内存统计
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();

        System.out.printf("内存使用: %.2fMB%n", usedMemory / 1024.0 / 1024.0);

        // 清理规则
        if ("INDEPENDENT".equals(strategyType)) {
            FlowRuleManager.loadRules(new ArrayList<>());
        } else {
            ParamFlowRuleManager.loadRules(new ArrayList<>());
        }
    }

    private void warmUp() {
        System.out.println("开始预热...");
        for (int i = 0; i < 5000; i++) {
            String ip = ipAddresses.get(i % ipCount);
            try {
                strategy.allowRequest(ip);
            } catch (Exception e) {
                // 忽略
            }
        }
        System.out.println("预热完成");
        MetricsCollector.reset();
    }

    private Object processBusiness() {
        long processTime = 500_000 + ThreadLocalRandom.current().nextLong(1_500_000);
        long start = System.nanoTime();
        while (System.nanoTime() - start < processTime) {
            // 忙等待
        }
        return "ProcessResult";
    }

    private Object fallback() {
        return "FallbackResult";
    }

    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(ComparisonPerformanceTest.class.getSimpleName())
                .shouldDoGC(true)
                .result("comparison_results.json")
                .resultFormat(org.openjdk.jmh.results.format.ResultFormatType.JSON)
                .build();

        new Runner(options).run();
    }
}

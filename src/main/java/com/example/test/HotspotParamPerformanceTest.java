package com.example.test;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
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
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(2)
public class HotspotParamPerformanceTest {

    @Param({"100", "1000", "5000", "10000"})
    private int ipCount;

    @Param({"0.1", "0.3", "0.5"})
    private double exceptionRatio;  // 例外项比例

    private List<String> ipAddresses;
    private HotspotParamStrategy strategy;

    // IP限流配置类
    public static class IpRateConfig {
        private final String ip;
        private final int qps;

        public IpRateConfig(String ip, int qps) {
            this.ip = ip;
            this.qps = qps;
        }

        public String getIp() { return ip; }
        public int getQps() { return qps; }
    }

    // 热点参数策略实现
    public static class HotspotParamStrategy {
        private static final String RESOURCE_NAME = "hotspot_ip_resource";
        private final Map<String, Integer> ipRateCache = new ConcurrentHashMap<>();
        private ParamFlowRule paramFlowRule;
        private List<ParamFlowItem> hotspotParams = new ArrayList<>();

        public void initRules(List<IpRateConfig> configs) {
            // 构建参数例外项列表
            hotspotParams = new ArrayList<>();

            for (IpRateConfig config : configs) {
                String ip = config.getIp();
                int qps = config.getQps();

                ipRateCache.put(ip, qps);

                // 只有当QPS不等于默认值100时，才作为例外项
                if (qps != 100) {
                    ParamFlowItem item = new ParamFlowItem();
                    item.setObject(ip);
                    item.setClassType(String.class.getTypeName());
                    item.setCount(qps);
                    hotspotParams.add(item);
                }
            }

            paramFlowRule = new ParamFlowRule(RESOURCE_NAME)
                    .setParamIdx(0)  // 第一个参数是IP地址
                    .setGrade(RuleConstant.FLOW_GRADE_QPS)
                    .setCount(100)   // 默认QPS限制
                    .setParamFlowItemList(hotspotParams);

            ParamFlowRuleManager.loadRules(Collections.singletonList(paramFlowRule));
        }

        public boolean allowRequest(String ip) {
            Entry entry = null;
            try {
                // 使用热点参数限流
                entry = SphU.entry(RESOURCE_NAME,
                        com.alibaba.csp.sentinel.EntryType.IN,
                        1,  // batch count
                        ip  // 参数值
                );
                return true;
            } catch (BlockException e) {
                return false;
            } finally {
                if (entry != null) {
                    entry.exit(1, ip);
                }
            }
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
        private static final List<Long> hotspotLatencies = Collections.synchronizedList(new ArrayList<>());
        private static final List<Long> defaultLatencies = Collections.synchronizedList(new ArrayList<>());

        public static void record(long latency, boolean passed, boolean isHotspot) {
            totalRequests.incrementAndGet();
            if (passed) {
                passedRequests.incrementAndGet();
            } else {
                blockedRequests.incrementAndGet();
            }
            latencies.add(latency);

            if (isHotspot) {
                hotspotLatencies.add(latency);
            } else {
                defaultLatencies.add(latency);
            }
        }

        public static void reset() {
            totalRequests.set(0);
            passedRequests.set(0);
            blockedRequests.set(0);
            latencies.clear();
            hotspotLatencies.clear();
            defaultLatencies.clear();
        }

        public static void printMetrics(int ipCount, double exceptionRatio) {
            if (totalRequests.get() == 0) {
                return;
            }

            System.out.println("\n" + "=".repeat(60));
            System.out.printf("热点参数方案性能统计 (IP数: %d, 例外比例: %.1f%%)%n",
                    ipCount, exceptionRatio * 100);
            System.out.println("=".repeat(60));

            long total = totalRequests.get();
            long passed = passedRequests.get();
            long blocked = blockedRequests.get();

            System.out.printf("总请求数: %,d%n", total);
            System.out.printf("通过请求: %,d (%.2f%%)%n",
                    passed, passed * 100.0 / total);
            System.out.printf("限流请求: %,d (%.2f%%)%n",
                    blocked, blocked * 100.0 / total);

            // 延迟统计
            printLatencyStats("整体", latencies);
            printLatencyStats("热点参数", hotspotLatencies);
            printLatencyStats("默认参数", defaultLatencies);
        }

        private static void printLatencyStats(String name, List<Long> latencies) {
            if (latencies.isEmpty()) {
                return;
            }

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

            System.out.printf("%n%s延迟统计:%n", name);
            System.out.printf("  样本数: %,d%n", size);
            System.out.printf("  平均: %.0f ns (%.2f ms)%n", avg, avg / 1_000_000.0);
            System.out.printf("  P50: %,d ns (%.2f ms)%n", p50, p50 / 1_000_000.0);
            System.out.printf("  P90: %,d ns (%.2f ms)%n", p90, p90 / 1_000_000.0);
            System.out.printf("  P95: %,d ns (%.2f ms)%n", p95, p95 / 1_000_000.0);
            System.out.printf("  P99: %,d ns (%.2f ms)%n", p99, p99 / 1_000_000.0);
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        // 重置指标收集器
        MetricsCollector.reset();

        // 生成IP地址
        ipAddresses = generateIpAddresses(ipCount);

        // 生成配置，部分IP有特殊限制
        List<IpRateConfig> configs = new ArrayList<>();
        Random random = new Random(42);
        int exceptionCount = (int) (ipCount * exceptionRatio);

        for (int i = 0; i < ipCount; i++) {
            String ip = ipAddresses.get(i);
            int qps;
            boolean isHotspot = i < exceptionCount;

            if (isHotspot) {
                // 例外项：不同的QPS限制
                qps = 50 + random.nextInt(200);  // 50-250
            } else {
                // 默认值
                qps = 100;
            }

            configs.add(new IpRateConfig(ip, qps));
        }

        strategy = new HotspotParamStrategy();
        long startTime = System.currentTimeMillis();
        strategy.initRules(configs);
        long loadTime = System.currentTimeMillis() - startTime;

        System.out.printf("%n热点参数方案初始化: IP数=%d, 例外比例=%.1f%%, 例外项数=%d, 耗时=%dms%n",
                ipCount, exceptionRatio * 100, exceptionCount, loadTime);

        // 预热
        warmUp();
    }

    @Benchmark
    @Threads(200)
    public void testHotspotParam(Blackhole blackhole) {
        int index = ThreadLocalRandom.current().nextInt(ipCount);
        String ip = ipAddresses.get(index);

        // 判断是否为热点IP（前exceptionRatio比例的IP）
        boolean isHotspot = index < (int)(ipCount * exceptionRatio);

        long start = System.nanoTime();
        boolean allowed = strategy.allowRequest(ip);
        long latency = System.nanoTime() - start;

        if (allowed) {
            blackhole.consume(processBusiness());
        } else {
            blackhole.consume(fallback());
        }

        MetricsCollector.record(latency, allowed, isHotspot);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // 打印性能指标
        MetricsCollector.printMetrics(ipCount, exceptionRatio);

        // 内存使用情况
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        System.out.printf("内存使用: %.2fMB / %.2fMB (%.1f%%)%n",
                usedMemory / 1024.0 / 1024.0,
                maxMemory / 1024.0 / 1024.0,
                usedMemory * 100.0 / maxMemory);

        // 清理规则
        ParamFlowRuleManager.loadRules(new ArrayList<>());
    }

    // 辅助方法
    private List<String> generateIpAddresses(int count) {
        List<String> ips = new ArrayList<>(count);
        Random random = new Random(42);

        for (int i = 0; i < count; i++) {
            String ip = String.format("10.%d.%d.%d",
                    random.nextInt(255),
                    (i / 65536) % 256,
                    i % 256);
            ips.add(ip);
        }
        return ips;
    }

    private void warmUp() {
        System.out.println("开始预热...");
        // 预热10000次请求
        for (int i = 0; i < 10000; i++) {
            String ip = ipAddresses.get(i % ipCount);
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

    // 主方法用于运行测试
    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(HotspotParamPerformanceTest.class.getSimpleName())
                .shouldDoGC(true)
                .result("hotspot_benchmark_results.json")
                .resultFormat(org.openjdk.jmh.results.format.ResultFormatType.JSON)
                .build();

        new Runner(options).run();
    }
}

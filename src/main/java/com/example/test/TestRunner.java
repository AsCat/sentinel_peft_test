package com.example.test;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.results.format.ResultFormatType;

import java.util.concurrent.TimeUnit;

public class TestRunner {

    public static void main(String[] args) throws RunnerException {
        if (args.length > 0) {
            // 根据参数选择运行特定测试
            String testType = args[0];
            switch (testType) {
                case "independent":
                    runIndependentRuleTest();
                    break;
                case "hotspot":
                    runHotspotParamTest();
                    break;
                case "comparison":
                    runComparisonTest();
                    break;
                case "quick":
                    runQuickTest();
                    break;
                case "full":
                    runFullTestSuite();
                    break;
                default:
                    runHelp();
            }
        } else {
            // 默认运行完整测试套件
            runFullTestSuite();
        }
    }

    /**
     * 运行完整测试套件
     */
    public static void runFullTestSuite() throws RunnerException {
        System.out.println("=".repeat(80));
        System.out.println("开始完整的Sentinel性能测试套件");
        System.out.println("=".repeat(80));
        System.out.println("测试时间: " + new java.util.Date());
        System.out.println("Java版本: " + System.getProperty("java.version"));
        System.out.println("JVM内存: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB");
        System.out.println("=".repeat(80));

        long startTime = System.currentTimeMillis();

        // 步骤1: 独立规则测试
        System.out.println("\n步骤1: 独立规则性能测试");
        System.out.println("-".repeat(60));
        runIndependentRuleTest();

        // 步骤2: 热点参数测试
        System.out.println("\n步骤2: 热点参数性能测试");
        System.out.println("-".repeat(60));
        runHotspotParamTest();

        // 步骤3: 对比测试
        System.out.println("\n步骤3: 策略对比性能测试");
        System.out.println("-".repeat(60));
        runComparisonTest();

        long endTime = System.currentTimeMillis();
        long duration = (endTime - startTime) / 1000;

        System.out.println("\n" + "=".repeat(80));
        System.out.printf("完整测试套件运行完成! 总耗时: %d分%d秒%n",
                duration / 60, duration % 60);
        System.out.println("测试结果已保存到以下文件:");
        System.out.println("  - independent_rule_results.json");
        System.out.println("  - hotspot_param_results.json");
        System.out.println("  - comparison_results.json");
        System.out.println("=".repeat(80));
    }

    /**
     * 运行独立规则测试
     */
    public static void runIndependentRuleTest() throws RunnerException {
        System.out.println("配置: 测试独立规则方案在不同规则数量下的性能");
        System.out.println("参数: ruleCount = [100, 500, 1000, 2000, 5000]");
        System.out.println("线程: 100线程并发");

        Options options = new OptionsBuilder()
                .include(IndependentRulePerformanceTest.class.getSimpleName())
                .param("ruleCount", "100", "500", "1000", "2000", "5000")
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(5))
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(10))
                .threads(100)
                .forks(1)
                .shouldDoGC(true)
                .result("independent_rule_results.json")
                .resultFormat(ResultFormatType.JSON)
                .jvmArgs("-Xmx4g", "-Xms4g", "-XX:+UseG1GC")
                .build();

        new Runner(options).run();
    }

    /**
     * 运行热点参数测试
     */
    public static void runHotspotParamTest() throws RunnerException {
        System.out.println("配置: 测试热点参数方案在不同规模和例外比例下的性能");
        System.out.println("参数: ipCount = [100, 1000, 5000, 10000]");
        System.out.println("参数: exceptionRatio = [0.1, 0.3, 0.5]");
        System.out.println("线程: 200线程并发");

        Options options = new OptionsBuilder()
                .include(HotspotParamPerformanceTest.class.getSimpleName())
                .param("ipCount", "100", "1000", "5000", "10000")
                .param("exceptionRatio", "0.1", "0.3", "0.5")
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(5))
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(10))
                .threads(200)
                .forks(1)
                .shouldDoGC(true)
                .result("hotspot_param_results.json")
                .resultFormat(ResultFormatType.JSON)
                .jvmArgs("-Xmx4g", "-Xms4g", "-XX:+UseG1GC")
                .build();

        new Runner(options).run();
    }

    /**
     * 运行对比测试
     */
    public static void runComparisonTest() throws RunnerException {
        System.out.println("配置: 对比独立规则和热点参数方案的性能差异");
        System.out.println("参数: ipCount = [10, 100, 1000, 5000]");
        System.out.println("策略: strategyType = [INDEPENDENT, HOTSPOT]");
        System.out.println("线程: 1线程并发");

        Options options = new OptionsBuilder()
                .include(ComparisonPerformanceTest.class.getSimpleName())
                .param("ipCount", "10", "100", "1000", "5000")
                .param("strategyType", "INDEPENDENT", "HOTSPOT")
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(5))
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(5))
                .threads(1)
                .forks(1)
                .shouldDoGC(true)
                .result("comparison_results.json")
                .resultFormat(ResultFormatType.JSON)
                .jvmArgs("-Xmx8g", "-Xms4g", "-XX:+UseG1GC")
                .build();

        new Runner(options).run();
    }

    /**
     * 运行快速测试（用于验证）
     */
    public static void runQuickTest() throws RunnerException {
        System.out.println("运行快速验证测试...");

        // 快速独立规则测试
        Options independentOptions = new OptionsBuilder()
                .include(IndependentRulePerformanceTest.class.getSimpleName())
                .param("ruleCount", "100", "500")
                .warmupIterations(1)
                .warmupTime(TimeValue.seconds(3))
                .measurementIterations(1)
                .measurementTime(TimeValue.seconds(5))
                .threads(50)
                .forks(0)
                .shouldDoGC(false)
                .build();

        // 快速热点参数测试
        Options hotspotOptions = new OptionsBuilder()
                .include(HotspotParamPerformanceTest.class.getSimpleName())
                .param("ipCount", "100", "500")
                .param("exceptionRatio", "0.1")
                .warmupIterations(1)
                .warmupTime(TimeValue.seconds(3))
                .measurementIterations(1)
                .measurementTime(TimeValue.seconds(5))
                .threads(50)
                .forks(0)
                .shouldDoGC(false)
                .build();

        // 快速对比测试
        Options comparisonOptions = new OptionsBuilder()
                .include(ComparisonPerformanceTest.class.getSimpleName())
                .param("ipCount", "100")
                .param("strategyType", "INDEPENDENT", "HOTSPOT")
                .warmupIterations(1)
                .warmupTime(TimeValue.seconds(3))
                .measurementIterations(1)
                .measurementTime(TimeValue.seconds(5))
                .threads(50)
                .forks(0)
                .shouldDoGC(false)
                .build();

        System.out.println("1. 快速独立规则测试...");
        new Runner(independentOptions).run();

        System.out.println("\n2. 快速热点参数测试...");
        new Runner(hotspotOptions).run();

        System.out.println("\n3. 快速对比测试...");
        new Runner(comparisonOptions).run();

        System.out.println("\n快速测试完成!");
    }

    /**
     * 显示帮助信息
     */
    public static void runHelp() {
        System.out.println("Sentinel性能测试套件 - 使用说明");
        System.out.println("=".repeat(60));
        System.out.println("用法: java -cp ... TestRunner [test_type]");
        System.out.println();
        System.out.println("可用的测试类型:");
        System.out.println("  independent    - 运行独立规则测试");
        System.out.println("  hotspot        - 运行热点参数测试");
        System.out.println("  comparison     - 运行对比测试");
        System.out.println("  quick          - 运行快速验证测试");
        System.out.println("  full           - 运行完整测试套件（默认）");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar benchmarks.jar independent");
        System.out.println("  java -cp ... TestRunner hotspot");
        System.out.println("  java -cp ... TestRunner full");
        System.out.println();
        System.out.println("测试结果:");
        System.out.println("  - 独立规则测试: independent_rule_results.json");
        System.out.println("  - 热点参数测试: hotspot_param_results.json");
        System.out.println("  - 对比测试: comparison_results.json");
    }


}

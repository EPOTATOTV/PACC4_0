package com.potatotv.pa2;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * PA2 与 BouncyCastle 在相同参数下的性能基准（验收 V02：差异 &lt; 15%）。
 *
 * <p>用 JMH 单独运行，不挂在 surefire 上：直接执行 {@link #main(String[])}（IDE 运行，
 * 或自行用测试 classpath 起 JVM）即可输出两种实现的平均耗时对比。</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Thread)
public class Argon2Benchmark {

    /** 生产参数：t=3、m=64MiB、p=1、T=32。 */
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KIB = 65536;
    private static final int PARALLELISM = 1;
    private static final int HASH_BYTES = 32;

    private Argon2 pa2;
    private Argon2BytesGenerator bouncyCastle;
    private byte[] password;
    private byte[] salt;
    private byte[] out;

    @Setup
    public void setup() {
        pa2 = new Argon2(ITERATIONS, MEMORY_KIB, PARALLELISM, HASH_BYTES);
        password = "Pacc@Test123".getBytes(StandardCharsets.UTF_8);
        salt = new byte[16];
        new Random(42L).nextBytes(salt);
        bouncyCastle = new Argon2BytesGenerator();
        bouncyCastle.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ITERATIONS)
                .withMemoryAsKB(MEMORY_KIB)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build());
        out = new byte[HASH_BYTES];
    }

    @Benchmark
    public byte[] pa2Hash() {
        return pa2.hash(password, salt);
    }

    @Benchmark
    public byte[] bouncyCastleHash() {
        bouncyCastle.generateBytes(password, out);
        return out;
    }

    public static void main(String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(Argon2Benchmark.class.getSimpleName())
                .build()).run();
    }
}
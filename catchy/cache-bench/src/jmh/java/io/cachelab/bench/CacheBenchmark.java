package io.cachelab.bench;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.cachelab.Cache;
import io.cachelab.CacheBuilder;
import io.cachelab.PolicyType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

/**
 * Throughput of get/put mixes over a Zipf (s = 1.0) key stream (SPEC 5): capacity 10,000, key space
 * 100,000, keys pre-generated so the benchmark measures the cache, not the random generator.
 *
 * <p>Implementations: {@code single} (one lock), {@code segmented} (16 segments), {@code
 * syncLinkedHashMap} (the classic {@code Collections.synchronizedMap} LRU baseline) and {@code
 * caffeine} (the reference ceiling). One {@code @Benchmark} method per thread count.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class CacheBenchmark {

  static final int CAPACITY = 10_000;
  static final int KEY_SPACE = 100_000;
  static final int STREAM = 1 << 20; // pre-generated accesses, cycled

  @Param({"single", "segmented", "syncLinkedHashMap", "caffeine"})
  public String impl;

  @Param({"read90", "mixed50", "write90"})
  public String workload;

  BenchCache cache;
  Integer[] keys;
  boolean[] reads;

  /** A thread's position in the shared key and operation streams. */
  @State(Scope.Thread)
  public static class Cursor {
    int index;

    @Setup(Level.Iteration)
    public void setUp() {
      index = (int) (Thread.currentThread().threadId() * 7919) & (STREAM - 1);
    }
  }

  @Setup(Level.Trial)
  public void setUp() {
    keys = ZipfKeys.generate(STREAM, KEY_SPACE, 1.0, 42);
    reads = operations(workload);
    cache = create(impl);
    for (int i = 0; i < CAPACITY * 2; i++) { // warm the cache with the key stream
      cache.put(keys[i], keys[i]);
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    cache.close();
  }

  @Benchmark
  @Threads(1)
  public Object threads01(Cursor c) {
    return step(c);
  }

  @Benchmark
  @Threads(4)
  public Object threads04(Cursor c) {
    return step(c);
  }

  @Benchmark
  @Threads(16)
  public Object threads16(Cursor c) {
    return step(c);
  }

  @Benchmark
  @Threads(32)
  public Object threads32(Cursor c) {
    return step(c);
  }

  private Object step(Cursor c) {
    int i = c.index++ & (STREAM - 1);
    Integer key = keys[i];
    if (reads[i]) {
      return cache.get(key);
    }
    cache.put(key, key);
    return key;
  }

  static boolean[] operations(String workload) {
    double readRatio =
        switch (workload) {
          case "read90" -> 0.9;
          case "mixed50" -> 0.5;
          case "write90" -> 0.1;
          default -> throw new IllegalArgumentException("unknown workload " + workload);
        };
    boolean[] reads = new boolean[STREAM];
    SplittableRandom rnd = new SplittableRandom(7);
    for (int i = 0; i < STREAM; i++) {
      reads[i] = rnd.nextDouble() < readRatio;
    }
    return reads;
  }

  static BenchCache create(String impl) {
    return switch (impl) {
      case "single" -> cachelab(1);
      case "segmented" -> cachelab(16);
      case "syncLinkedHashMap" -> syncLinkedHashMap();
      case "caffeine" -> caffeine();
      default -> throw new IllegalArgumentException("unknown impl " + impl);
    };
  }

  private static BenchCache cachelab(int segments) {
    Cache<Integer, Integer> c =
        CacheBuilder.<Integer, Integer>newBuilder()
            .name("bench-" + segments)
            .maximumSize(CAPACITY)
            .evictionPolicy(PolicyType.LRU)
            .concurrencyLevel(segments)
            .build();
    return new BenchCache() {
      public Object get(Integer k) {
        return c.get(k).orElse(null);
      }

      public void put(Integer k, Integer v) {
        c.put(k, v);
      }

      public void close() {
        c.close();
      }
    };
  }

  private static BenchCache syncLinkedHashMap() {
    Map<Integer, Integer> m =
        Collections.synchronizedMap(
            new LinkedHashMap<>(CAPACITY * 2, 0.75f, true) {
              @Override
              protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                return size() > CAPACITY;
              }
            });
    return new BenchCache() {
      public Object get(Integer k) {
        return m.get(k);
      }

      public void put(Integer k, Integer v) {
        m.put(k, v);
      }

      public void close() {}
    };
  }

  private static BenchCache caffeine() {
    com.github.benmanes.caffeine.cache.Cache<Integer, Integer> c =
        Caffeine.newBuilder().maximumSize(CAPACITY).build();
    return new BenchCache() {
      public Object get(Integer k) {
        return c.getIfPresent(k);
      }

      public void put(Integer k, Integer v) {
        c.put(k, v);
      }

      public void close() {}
    };
  }

  /** The operations every implementation supports. */
  interface BenchCache {
    Object get(Integer key);

    void put(Integer key, Integer value);

    void close();
  }
}

package dev.harborwatch.load;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Implements CPU/memory/DB/combined stress routines.
 * - CPU: tight math loop (bounded) to avoid freezing small instances.
 * - Memory: allocate byte[] blocks and touch them.
 * - DB: many INSERTs + occasional SELECTs.
 * All durations are wall-clock millis measured inside each routine.
 */
@Service
public class LoadService {
    private static final Logger log = LoggerFactory.getLogger(LoadService.class);
    private final JdbcTemplate jdbc;
    private static final SecureRandom RAND = new SecureRandom();

    public LoadService(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    /** CPU-bound loop; iterations are capped for safety. */
    public Map<String, Object> cpuIntensive(long iterations) {
        long cap = Math.min(iterations, 7_000_000L);
        long start = System.nanoTime();
        double acc = 0d;

        for (long i = 1; i <= cap; i++) {
            acc += Math.sqrt(i) * Math.sin(i * 0.0001);
            if ((i & 0x7FFFF) == 0) { /* light branch to prevent JIT flattening */ }
        }

        long durMs = (System.nanoTime() - start) / 1_000_000;
        Map<String, Object> out = new HashMap<>();
        out.put("type", "cpu_intensive");
        out.put("iterations", cap);
        out.put("acc", acc);
        out.put("duration_ms", durMs);
        return out;
    }

    /** Allocate and touch memory in MB chunks; returns checksum to prevent dead-code elim. */
    public Map<String, Object> memoryIntensive(int sizeMb) {
        int capMb = Math.min(Math.max(sizeMb, 1), 1000); // keep sane
        long start = System.nanoTime();
        long checksum = 0L;

        byte[][] blocks = new byte[capMb][];
        for (int m = 0; m < capMb; m++) {
            blocks[m] = new byte[1024 * 1024];
            // touch a few bytes to actually back memory
            for (int i = 0; i < 1024 * 1024; i += 4096) {
                blocks[m][i] = (byte) (i ^ m);
                checksum += blocks[m][i];
            }
        }

        long durMs = (System.nanoTime() - start) / 1_000_000;
        Map<String, Object> out = new HashMap<>();
        out.put("type", "memory_intensive");
        out.put("sizeMb", capMb);
        out.put("checksum", checksum);
        out.put("duration_ms", durMs);
        return out;
    }

    /** Many INSERTs + occasional SELECTs to exercise DB. */
    public Map<String, Object> databaseIntensive(int ops) {
        int capOps = Math.min(Math.max(ops, 1), 10_000);
        long start = System.nanoTime();

        for (int i = 1; i <= capOps; i++) {
            jdbc.update(
                "INSERT INTO performance_data (timestamp, metric_name, metric_value, metadata) VALUES (NOW(), ?, ?, '{}'::jsonb)",
                "test_metric_" + i,
                RAND.nextDouble() * 100.0
            );

            if (i % 25 == 0) {
                jdbc.queryForList(
                    "SELECT id FROM performance_data WHERE metric_name LIKE 'test_metric_%' ORDER BY id DESC LIMIT 5"
                );
            }
        }

        long durMs = (System.nanoTime() - start) / 1_000_000;
        Map<String, Object> out = new HashMap<>();
        out.put("type", "database_intensive");
        out.put("ops", capOps);
        out.put("duration_ms", durMs);
        return out;
    }

    /** Parallel mix: CPU + Mem + DB for given seconds. */
    public Map<String, Object> combinedStress(int durationSec) {
        int sec = Math.min(Math.max(durationSec, 1), 60);
        long start = System.nanoTime();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        long endAt = System.currentTimeMillis() + sec * 1000L;
        int[] counts = new int[]{0};

        try {
            while (System.currentTimeMillis() < endAt) {
                pool.submit(() -> {
                    cpuIntensive(250_000L);
                    counts[0]++;
                });
                pool.submit(() -> {
                    memoryIntensive(10);
                    counts[0]++;
                });
                pool.submit(() -> {
                    databaseIntensive(50);
                    counts[0]++;
                });
                Thread.sleep(250);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
            try { pool.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }

        long durMs = (System.nanoTime() - start) / 1_000_000;
        Map<String, Object> out = new HashMap<>();
        out.put("type", "combined_stress");
        out.put("duration_seconds", sec);
        out.put("tasks_submitted", counts[0]);
        out.put("duration_ms", durMs);
        return out;
    }
}

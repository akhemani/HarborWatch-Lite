package dev.harborwatch.load;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST facade for the stress routines.
 * Each endpoint also writes a summary row to computation_results.
 */
@RestController
@RequestMapping("/api")
public class LoadController {
    private static final Logger log = LoggerFactory.getLogger(LoadController.class);
    private final LoadService load;
    private final JdbcTemplate jdbc;

    public LoadController(LoadService loadService, JdbcTemplate jdbcTemplate) {
        this.load = loadService;
        this.jdbc = jdbcTemplate;
    }

    @GetMapping("/cpu-intensive")
    public Map<String, Object> cpu(@RequestParam(defaultValue = "1000000") long iterations) {
        Map<String, Object> res = load.cpuIntensive(iterations);
        jdbc.update(
            "INSERT INTO computation_results (timestamp, computation_type, input_size, result, duration_ms) VALUES (NOW(), ?, ?, ?, ?)",
            "cpu_intensive", (long) res.get("iterations"),
            "acc=" + res.get("acc"), (long) ( (Number) res.get("duration_ms") ).longValue()
        );
        log.info("cpu-intensive done iter={} durMs={}", res.get("iterations"), res.get("duration_ms"));
        return res;
    }

    @GetMapping("/memory-intensive")
    public Map<String, Object> mem(@RequestParam(name="sizeMb", defaultValue = "50") int sizeMb) {
        Map<String, Object> res = load.memoryIntensive(sizeMb);
        jdbc.update(
            "INSERT INTO computation_results (timestamp, computation_type, input_size, result, duration_ms) VALUES (NOW(), ?, ?, ?, ?)",
            "memory_intensive", (int) res.get("sizeMb"),
            "checksum=" + res.get("checksum"), (long) ( (Number) res.get("duration_ms") ).longValue()
        );
        log.info("memory-intensive done sizeMb={} durMs={}", res.get("sizeMb"), res.get("duration_ms"));
        return res;
    }

    @GetMapping("/database-intensive")
    public Map<String, Object> db(@RequestParam(name="ops", defaultValue = "1000") int ops) {
        Map<String, Object> res = load.databaseIntensive(ops);
        jdbc.update(
            "INSERT INTO computation_results (timestamp, computation_type, input_size, result, duration_ms) VALUES (NOW(), ?, ?, ?, ?)",
            "database_intensive", (int) res.get("ops"),
            "ok", (long) ( (Number) res.get("duration_ms") ).longValue()
        );
        log.info("database-intensive done ops={} durMs={}", res.get("ops"), res.get("duration_ms"));
        return res;
    }

    @GetMapping("/combined-stress")
    public Map<String, Object> combined(@RequestParam(name="durationSec", defaultValue = "20") int durationSec) {
        Map<String, Object> res = load.combinedStress(durationSec);
        jdbc.update(
            "INSERT INTO computation_results (timestamp, computation_type, input_size, result, duration_ms) VALUES (NOW(), ?, ?, ?, ?)",
            "combined_stress", (int) res.get("duration_seconds"),
            "tasks=" + res.get("tasks_submitted"), (long) ( (Number) res.get("duration_ms") ).longValue()
        );
        log.info("combined-stress done durSec={} tasks={} durMs={}",
                res.get("duration_seconds"), res.get("tasks_submitted"), res.get("duration_ms"));
        return res;
    }
}

package dev.harborwatch.debug;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only debug endpoints to inspect DB and clocks without SQL shell.
 * Remove or secure in production.
 */
@RestController
@RequestMapping("/api/debug")
public class DebugController {
    private final JdbcTemplate jdbc;

    public DebugController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Long perf = jdbc.queryForObject("SELECT COUNT(*) FROM performance_data", Long.class);
        Long comp = jdbc.queryForObject("SELECT COUNT(*) FROM computation_results", Long.class);
        return Map.of("performance_data", perf, "computation_results", comp);
    }

    @GetMapping("/performance/recent")
    public List<Map<String, Object>> perfRecent() {
        return jdbc.queryForList("""
            SELECT id, timestamp, metric_name, metric_value, metadata
            FROM performance_data ORDER BY id DESC LIMIT 10
        """);
    }

    @GetMapping("/computations/recent")
    public List<Map<String, Object>> compRecent() {
        return jdbc.queryForList("""
            SELECT id, timestamp, computation_type, input_size, duration_ms, LEFT(result, 120) AS result
            FROM computation_results ORDER BY id DESC LIMIT 10
        """);
    }

    @GetMapping("/now")
    public Map<String, Object> now() {
        String dbNow = jdbc.queryForObject("SELECT now()::text", String.class);
        String appNow = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).toString();
        return Map.of("app_now_ist", appNow, "db_now", dbNow);
    }
}

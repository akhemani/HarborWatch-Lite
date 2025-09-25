package dev.harborwatch.load;

import java.time.ZonedDateTime;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 5s writes four sample metrics into performance_data with a single timestamp.
 * This helps you see "time-bucketed" inserts.
 */
@Component
public class MetricsScheduler {
    private static final Logger log = LoggerFactory.getLogger(MetricsScheduler.class);
    private final JdbcTemplate jdbc;

    public MetricsScheduler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedRate = 5000, initialDelay = 3000)
    public void tick() {
        var now = ZonedDateTime.now();
        double cpu = 10 + ThreadLocalRandom.current().nextDouble() * 80;
        double mem = 5 + ThreadLocalRandom.current().nextDouble() * 60;
        int req  = 100 + ThreadLocalRandom.current().nextInt(500);
        double err = ThreadLocalRandom.current().nextDouble() * 10;

        log.info("scheduler tick START appTimeIST={} cpu={} mem={} req={} err={}%",
                now, String.format("%.1f", cpu), String.format("%.1f", mem), req, String.format("%.1f", err));

        // Use the same NOW() for all 4 rows (server-side timestamp).
        jdbc.update("INSERT INTO performance_data (timestamp, metric_name, metric_value, metadata) VALUES (NOW(), 'cpu_load', ?, '{}'::jsonb)", cpu);
        jdbc.update("INSERT INTO performance_data (timestamp, metric_name, metric_value, metadata) VALUES (NOW(), 'memory_usage', ?, '{}'::jsonb)", mem);
        jdbc.update("INSERT INTO performance_data (timestamp, metric_name, metric_value, metadata) VALUES (NOW(), 'request_count', ?, '{}'::jsonb)", (double) req);
        jdbc.update("INSERT INTO performance_data (timestamp, metric_name, metric_value, metadata) VALUES (NOW(), 'error_rate', ?, '{}'::jsonb)", err);

        log.info("scheduler tick DONE");
    }
}

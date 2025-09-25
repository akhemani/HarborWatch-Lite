package dev.harborwatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HarborWatch-Lite entrypoint.
 * - @EnableScheduling powers the 5s MetricsScheduler.
 */
@SpringBootApplication
@EnableScheduling
public class HarborWatchApplication {
    public static void main(String[] args) {
        SpringApplication.run(HarborWatchApplication.class, args);
    }
}

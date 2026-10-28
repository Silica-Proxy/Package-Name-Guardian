/*
 * Copyright 2026 SilicaProxy Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.silicaproxy.packagenameguardian.config;

import com.silicaproxy.packagenameguardian.service.monitoring.MonitoringService;
import com.silicaproxy.packagenameguardian.service.monitoring.MonitoringService.ComponentHealth;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceDataCache;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceSnapshot;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the same per-component checks already computed by {@link MonitoringService} for
 * {@code GET /api/monitoring/health} as standard Spring Boot {@link HealthIndicator} beans, so
 * they also appear under {@code GET /actuator/health} (component keys: {@code database},
 * {@code referenceDataFreshness}). No check logic is duplicated here -- each bean only adapts
 * {@link ComponentHealth} to {@link Health}.
 */
@Configuration
@NullMarked
public class HealthIndicatorsConfig {

    private static final Status DEGRADED = new Status("DEGRADED");

    private static Health toHealth(ComponentHealth componentHealth) {
        Health.Builder builder = switch (componentHealth.status()) {
            case "UP" -> Health.up();
            case "DOWN" -> Health.down();
            default -> Health.status(DEGRADED);
        };
        return builder.withDetails(componentHealth.details()).build();
    }

    @Bean
    public HealthIndicator databaseHealthIndicator(MonitoringService monitoringService) {
        return () -> toHealth(monitoringService.databaseHealth());
    }

    @Bean
    public HealthIndicator referenceDataFreshnessHealthIndicator(MonitoringService monitoringService) {
        return () -> toHealth(monitoringService.referenceDataFreshnessHealth());
    }

    /**
     * Health of the in-memory cache actually served by {@code POST /v1/check} (unlike
     * {@code database}, which only probes PostgreSQL): DOWN when no snapshot was ever loaded,
     * DEGRADED when the last successful reload is older than 10 refresh intervals (reload()
     * failing in a loop keeps the previous snapshot, so it would otherwise look healthy).
     */
    @Bean
    public HealthIndicator referenceCacheHealthIndicator(
            ReferenceDataCache cache,
            @Value("${packagenameguardian.allowlist.refresh-interval-ms:60000}") long refreshIntervalMs) {
        return () -> {
            ReferenceSnapshot snapshot = cache.current();
            if (snapshot == null) {
                return Health.down().withDetail("reason", "reference cache not loaded").build();
            }
            Duration age = Duration.between(snapshot.loadedAt(), Instant.now());
            Duration maxAge = Duration.ofMillis(refreshIntervalMs * 10);
            Health.Builder builder = age.compareTo(maxAge) > 0 ? Health.status(DEGRADED) : Health.up();
            return builder
                    .withDetail("loadedAt", snapshot.loadedAt().toString())
                    .withDetail("ageSeconds", age.toSeconds())
                    .withDetail("maxAgeSeconds", maxAge.toSeconds())
                    .build();
        };
    }
}

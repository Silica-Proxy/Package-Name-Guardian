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


package com.silicaproxy.packagenameguardian.service.monitoring;

import com.silicaproxy.packagenameguardian.dao.repository.HealthCheckRepository;
import com.silicaproxy.packagenameguardian.dao.repository.ReferencePackageRepository;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;

/**
 * Aggregates the global health status of packagenameguardian (DB, reference-data freshness) into
 * an UP/DOWN/DEGRADED status. Called only by {@code MonitoringController}, on demand, via
 * {@code GET /api/monitoring/health}, and adapted to {@code HealthIndicator} beans in
 * {@code HealthIndicatorsConfig} for {@code GET /actuator/health}.
 */
@Service
@NullMarked
public class MonitoringService {

    private final HealthCheckRepository healthCheckRepository;
    private final ReferencePackageRepository referencePackageRepository;
    private final PackageNameGuardianProperties properties;

    public MonitoringService(
            HealthCheckRepository healthCheckRepository,
            ReferencePackageRepository referencePackageRepository,
            PackageNameGuardianProperties properties) {
        this.healthCheckRepository = healthCheckRepository;
        this.referencePackageRepository = referencePackageRepository;
        this.properties = properties;
    }

    public record ComponentHealth(String status, Map<String, Object> details) {
        public ComponentHealth {
            details = Collections.unmodifiableMap(new HashMap<>(details));
        }
    }

    public record HealthReport(String status, Map<String, ComponentHealth> components) {
        public HealthReport {
            components = Collections.unmodifiableMap(new HashMap<>(components));
        }
    }

    public HealthReport checkHealth() {
        Map<String, ComponentHealth> components = new HashMap<>();
        components.put("database", databaseHealth());
        components.put("referenceDataFreshness", referenceDataFreshnessHealth());

        String overallStatus = "UP";
        for (ComponentHealth component : components.values()) {
            overallStatus = worseOf(overallStatus, component.status());
        }
        return new HealthReport(overallStatus, components);
    }

    private static String worseOf(String currentWorst, String candidate) {
        if ("DOWN".equals(currentWorst) || "DOWN".equals(candidate)) {
            return "DOWN";
        }
        if ("DEGRADED".equals(currentWorst) || "DEGRADED".equals(candidate)) {
            return "DEGRADED";
        }
        return "UP";
    }

    // Public: reused directly by the HealthIndicator beans in HealthIndicatorsConfig (a
    // different package) so /actuator/health reports the exact same per-component data as
    // GET /api/monitoring/health, instead of duplicating the check logic.
    public ComponentHealth databaseHealth() {
        Map<String, Object> details = new HashMap<>();
        try {
            healthCheckRepository.isDatabaseReachable();
            details.put("message", "Database connection OK");
            return new ComponentHealth("UP", details);
        } catch (RuntimeException e) {
            details.put("error", e.getMessage());
            return new ComponentHealth("DOWN", details);
        }
    }

    public ComponentHealth referenceDataFreshnessHealth() {
        Map<String, Object> details = new HashMap<>();
        Map<String, Long> countByEcosystem = referencePackageRepository.countByEcosystem();
        details.put("packageCounts", countByEcosystem);

        int minAcceptable = properties.referenceData().minAcceptableCountPerEcosystem();
        for (String ecosystem : new String[] {"NPM", "PYPI", "MAVEN"}) {
            long count = countByEcosystem.getOrDefault(ecosystem, 0L);
            if (count < minAcceptable) {
                details.put("message", ("reference_package has %d rows for %s (minimum %d) -- has the "
                        + "R__reference_data_seed.sql Flyway migration run?").formatted(count, ecosystem, minAcceptable));
                return new ComponentHealth("DOWN", details);
            }
        }

        Optional<Instant> lastApplied = healthCheckRepository.referenceDataSeedLastAppliedAt();
        if (lastApplied.isEmpty()) {
            details.put("message",
                    "reference_package is populated but no Flyway history row was found for the seed migration");
            return new ComponentHealth("DEGRADED", details);
        }

        Instant lastAppliedAt = lastApplied.get();
        details.put("lastSeedAppliedAt", lastAppliedAt.toString());
        long hoursSinceApplied = Duration.between(lastAppliedAt, Instant.now()).toHours();
        details.put("hoursSinceLastSeedApplied", hoursSinceApplied);

        long freshnessMaxAgeHours = properties.referenceData().freshnessMaxAgeHours();
        if (hoursSinceApplied > freshnessMaxAgeHours) {
            details.put("message", "Reference data seed is stale (missed at least one scheduled monthly refresh)");
            return new ComponentHealth("DEGRADED", details);
        }
        return new ComponentHealth("UP", details);
    }
}

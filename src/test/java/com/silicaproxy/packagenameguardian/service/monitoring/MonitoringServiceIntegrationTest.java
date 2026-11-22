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

import static org.assertj.core.api.Assertions.assertThat;

import com.silicaproxy.packagenameguardian.BaseIntegrationTest;
import com.silicaproxy.packagenameguardian.service.monitoring.MonitoringService.ComponentHealth;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

// The Testcontainers database is freshly created and seeded by Flyway at startup, so
// flyway_schema_history.installed_on is "now" -- exactly the recreated-database scenario where
// freshness must follow reference_data_meta.generated_at instead.
class MonitoringServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MonitoringService monitoringService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Timestamp originalGeneratedAt;

    @BeforeEach
    void rememberGeneratedAt() {
        originalGeneratedAt = jdbcTemplate.queryForObject(
                "SELECT generated_at FROM reference_data_meta", Timestamp.class);
    }

    @AfterEach
    void restoreGeneratedAt() {
        jdbcTemplate.update("DELETE FROM reference_data_meta");
        jdbcTemplate.update("INSERT INTO reference_data_meta (generated_at) VALUES (?)", originalGeneratedAt);
    }

    @Test
    void freshnessIsDegradedOnFreshlyAppliedDatabaseWhenSeedWasGeneratedNinetyDaysAgo() {
        setGeneratedAt(Instant.now().minus(Duration.ofDays(90)));

        ComponentHealth health = monitoringService.referenceDataFreshnessHealth();

        assertThat(health.status()).isEqualTo("DEGRADED");
    }

    @Test
    void freshnessIsUpWhenSeedWasGeneratedRecently() {
        setGeneratedAt(Instant.now().minus(Duration.ofDays(10)));

        ComponentHealth health = monitoringService.referenceDataFreshnessHealth();

        assertThat(health.status()).isEqualTo("UP");
    }

    @Test
    void freshnessIsDegradedWhenNoGeneratedAtRowExists() {
        jdbcTemplate.update("DELETE FROM reference_data_meta");

        ComponentHealth health = monitoringService.referenceDataFreshnessHealth();

        assertThat(health.status()).isEqualTo("DEGRADED");
    }

    private void setGeneratedAt(Instant generatedAt) {
        jdbcTemplate.update("UPDATE reference_data_meta SET generated_at = ?", Timestamp.from(generatedAt));
    }
}

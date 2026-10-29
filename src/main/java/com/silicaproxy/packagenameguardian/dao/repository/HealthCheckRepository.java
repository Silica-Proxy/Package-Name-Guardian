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


package com.silicaproxy.packagenameguardian.dao.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Cross-cutting DB introspection backing {@code MonitoringService}'s health checks: plain
 * connectivity, plus when the reference-data seed currently loaded was generated.
 */
@Repository
@NullMarked
public class HealthCheckRepository {

    private final JdbcClient jdbcClient;

    public HealthCheckRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Throws on any JDBC failure -- callers (MonitoringService) treat that as DOWN.
    public void isDatabaseReachable() {
        jdbcClient.sql("SELECT 1").query().singleRow();
    }

    // Reads the generation timestamp the seed migration itself writes, rather than
    // flyway_schema_history.installed_on: installed_on is "now" whenever the seed is applied to a
    // freshly created database, so months-old data would look fresh. generated_at is the age of
    // the data itself, which is what freshness monitoring needs.
    public Optional<Instant> referenceDataGeneratedAt() {
        List<Map<String, Object>> rows = jdbcClient.sql("SELECT generated_at FROM reference_data_meta")
                .query()
                .listOfRows();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object value = rows.get(0).get("generated_at");
        return value == null ? Optional.empty() : Optional.of(((Timestamp) value).toInstant());
    }
}

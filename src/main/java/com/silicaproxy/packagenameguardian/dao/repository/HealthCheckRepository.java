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
 * connectivity, plus when the reference-data seed migration was last actually applied.
 */
@Repository
@NullMarked
public class HealthCheckRepository {

    private static final String SEED_MIGRATION_SCRIPT = "R__reference_data_seed.sql";

    private final JdbcClient jdbcClient;

    public HealthCheckRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Throws on any JDBC failure -- callers (MonitoringService) treat that as DOWN.
    public void isDatabaseReachable() {
        jdbcClient.sql("SELECT 1").query().singleRow();
    }

    // Assumes the default flyway_schema_history table name (application.yaml never sets
    // spring.flyway.table) -- update this query if that ever changes. installed_on is only
    // updated for a repeatable migration (R__...) when its checksum actually changes and Flyway
    // re-applies it, which is exactly the "when was reference data last (re)loaded into this
    // database" signal freshness monitoring needs -- not "when did the app last start".
    public Optional<Instant> referenceDataSeedLastAppliedAt() {
        List<Map<String, Object>> rows = jdbcClient.sql("""
                SELECT installed_on FROM flyway_schema_history
                WHERE script = :script AND success = true
                ORDER BY installed_rank DESC
                LIMIT 1
                """)
                .param("script", SEED_MIGRATION_SCRIPT)
                .query()
                .listOfRows();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object value = rows.get(0).get("installed_on");
        return value == null ? Optional.empty() : Optional.of(((Timestamp) value).toInstant());
    }
}

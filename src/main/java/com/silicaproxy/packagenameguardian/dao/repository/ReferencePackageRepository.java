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

import com.silicaproxy.packagenameguardian.model.entity.ReferencePackage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to {@code reference_package}: rows are seeded exclusively by the
 * {@code R__reference_data_seed.sql} Flyway repeatable migration, never written by the running
 * application.
 */
@Repository
@NullMarked
public class ReferencePackageRepository {

    private final JdbcClient jdbcClient;

    public ReferencePackageRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ReferencePackage> findAll() {
        List<Map<String, Object>> rows = jdbcClient.sql("""
                SELECT ecosystem, package_name, dependent_count, popularity_rank
                FROM reference_package
                """)
                .query()
                .listOfRows();

        List<ReferencePackage> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new ReferencePackage(
                    (String) row.get("ecosystem"),
                    (String) row.get("package_name"),
                    ((Number) row.get("dependent_count")).longValue(),
                    ((Number) row.get("popularity_rank")).intValue()));
        }
        return result;
    }

    public Map<String, Long> countByEcosystem() {
        List<Map<String, Object>> rows = jdbcClient.sql("""
                SELECT ecosystem, COUNT(*) AS cnt FROM reference_package GROUP BY ecosystem
                """)
                .query()
                .listOfRows();

        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put((String) row.get("ecosystem"), ((Number) row.get("cnt")).longValue());
        }
        return counts;
    }
}

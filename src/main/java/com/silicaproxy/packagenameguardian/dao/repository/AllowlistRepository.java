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

import com.silicaproxy.packagenameguardian.model.entity.AllowlistEntry;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-write access to {@code package_allowlist} -- unlike {@link ReferencePackageRepository},
 * this table IS written at runtime, by {@code AllowlistController}. {@code insert} lets a unique
 * (ecosystem, package_name) violation propagate as {@code DataIntegrityViolationException}; the
 * caller decides how to translate that into an HTTP response.
 */
@Repository
@NullMarked
public class AllowlistRepository {

    private final JdbcClient jdbcClient;

    public AllowlistRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AllowlistEntry> findAll() {
        List<Map<String, Object>> rows = jdbcClient.sql("""
                SELECT ecosystem, package_name, created_at
                FROM package_allowlist
                """)
                .query()
                .listOfRows();

        List<AllowlistEntry> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new AllowlistEntry(
                    (String) row.get("ecosystem"),
                    (String) row.get("package_name"),
                    ((Timestamp) row.get("created_at")).toInstant()));
        }
        return result;
    }

    public List<AllowlistEntry> findByEcosystem(String ecosystem) {
        List<Map<String, Object>> rows = jdbcClient.sql("""
                SELECT ecosystem, package_name, created_at
                FROM package_allowlist
                WHERE ecosystem = :ecosystem
                """)
                .param("ecosystem", ecosystem)
                .query()
                .listOfRows();

        List<AllowlistEntry> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new AllowlistEntry(
                    (String) row.get("ecosystem"),
                    (String) row.get("package_name"),
                    ((Timestamp) row.get("created_at")).toInstant()));
        }
        return result;
    }

    public void insert(String ecosystem, String packageName) {
        jdbcClient.sql("""
                INSERT INTO package_allowlist (ecosystem, package_name)
                VALUES (:ecosystem, :packageName)
                """)
                .param("ecosystem", ecosystem)
                .param("packageName", packageName)
                .update();
    }

    public int delete(String ecosystem, String packageName) {
        return jdbcClient.sql("""
                DELETE FROM package_allowlist
                WHERE ecosystem = :ecosystem AND package_name = :packageName
                """)
                .param("ecosystem", ecosystem)
                .param("packageName", packageName)
                .update();
    }
}

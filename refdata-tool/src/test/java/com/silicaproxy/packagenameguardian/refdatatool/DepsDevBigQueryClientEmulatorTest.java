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


package com.silicaproxy.packagenameguardian.refdatatool;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.NoCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.DatasetInfo;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.silicaproxy.packagenameguardian.refdatatool.DepsDevBigQueryClient.FetchResult;
import com.silicaproxy.packagenameguardian.refdatatool.DepsDevBigQueryClient.PackageRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.gcloud.BigQueryEmulatorContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises {@link DepsDevBigQueryClient}'s real query logic (the 2-step, partition-shaped
 * snapshot lookup + ranking query with named parameters, window function, and nested STRUCT field
 * access) against a real BigQuery SQL engine via a Testcontainers emulator
 * (https://java.testcontainers.org/modules/gcloud/) -- no GCP credentials or cost required, unlike
 * the opt-in {@code bigquery-live} smoke test. {@link BigQueryServiceFactory} is the seam this
 * test overrides; {@code DepsDevBigQueryClient} itself has no test/emulator awareness.
 */
@Testcontainers
class DepsDevBigQueryClientEmulatorTest {

    // BigQueryEmulatorContainer's own constructor hardcodes "--project test-project" as the
    // emulator process's only known project; withCommand(...) here overrides that (last call
    // wins, before start()) to match the `bigquery-public-data` project DepsDevBigQueryClient's
    // real query text is hardcoded to reference -- entirely test-side, no production changes.
    @Container
    private static final BigQueryEmulatorContainer EMULATOR = new BigQueryEmulatorContainer("ghcr.io/goccy/bigquery-emulator:latest")
            .withCommand("--project", "bigquery-public-data");

    private static final long MAXIMUM_BYTES_BILLED = 1_000_000_000_000L;

    @BeforeAll
    static void seedEmulator() {
        BigQuery bigQuery = emulatorBigQuery();

        bigQuery.create(DatasetInfo.newBuilder("deps_dev_v1").build());

        bigQuery.create(TableInfo.newBuilder(
                TableId.of("deps_dev_v1", "Snapshots"),
                StandardTableDefinition.of(Schema.of(Field.of("Time", LegacySQLTypeName.TIMESTAMP))))
                .build());

        bigQuery.create(TableInfo.newBuilder(
                TableId.of("deps_dev_v1", "Dependents"),
                StandardTableDefinition.of(Schema.of(
                        Field.of("System", LegacySQLTypeName.STRING),
                        Field.of("Name", LegacySQLTypeName.STRING),
                        Field.of("Dependent", LegacySQLTypeName.RECORD, Field.of("Name", LegacySQLTypeName.STRING)),
                        Field.of("DependentIsHighestReleaseWithResolution", LegacySQLTypeName.BOOLEAN),
                        Field.of("SnapshotAt", LegacySQLTypeName.TIMESTAMP))))
                .build());

        Instant snapshotAt = Instant.parse("2026-07-13T00:00:00Z");
        bigQuery.insertAll(InsertAllRequest.newBuilder(TableId.of("deps_dev_v1", "Snapshots"))
                .addRow(Map.of("Time", snapshotAt.toString()))
                .build());

        // 2 npm packages: "popular" has 2 distinct dependents, "obscure" has 1 -- so "popular"
        // must rank #1. A row from an older snapshot must never be counted.
        InsertAllRequest.Builder dependents = InsertAllRequest.newBuilder(TableId.of("deps_dev_v1", "Dependents"));
        dependents.addRow(dependentRow("NPM", "popular", "app-one", snapshotAt));
        dependents.addRow(dependentRow("NPM", "popular", "app-two", snapshotAt));
        dependents.addRow(dependentRow("NPM", "obscure", "app-one", snapshotAt));
        dependents.addRow(dependentRow("NPM", "popular", "app-three", snapshotAt.minusSeconds(86400)));
        bigQuery.insertAll(dependents.build());
    }

    private static Map<String, Object> dependentRow(String system, String name, String dependentName, Instant snapshotAt) {
        return Map.of(
                "System", system,
                "Name", name,
                "Dependent", Map.of("Name", dependentName),
                "DependentIsHighestReleaseWithResolution", true,
                "SnapshotAt", snapshotAt.toString());
    }

    // DepsDevBigQueryClient's real query hardcodes the `bigquery-public-data` project (it's a
    // public dataset, never expected to change) -- override the emulator's own default
    // ("test-project") so the dataset/tables seeded below actually match what the query expects.
    private static BigQuery emulatorBigQuery() {
        return BigQueryOptions.newBuilder()
                .setHost(EMULATOR.getEmulatorHttpEndpoint())
                .setProjectId("bigquery-public-data")
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
    }

    @Test
    void fetchesRankedPackagesFromTheEmulator() {
        BigQueryServiceFactory testFactory = DepsDevBigQueryClientEmulatorTest::emulatorBigQuery;
        DepsDevBigQueryClient client = new DepsDevBigQueryClient(testFactory, MAXIMUM_BYTES_BILLED);

        FetchResult result = client.fetchTopPackages(10);

        Map<String, List<PackageRow>> byEcosystem =
                result.rows().stream().collect(Collectors.groupingBy(PackageRow::ecosystem));
        List<PackageRow> npm = byEcosystem.get("NPM");
        assertThat(npm).extracting(PackageRow::packageName).containsExactly("popular", "obscure");
        assertThat(npm.get(0).dependentCount()).isEqualTo(2);
        assertThat(npm.get(0).popularityRank()).isEqualTo(1);
        assertThat(npm.get(1).dependentCount()).isEqualTo(1);
    }

    // Regression test: a dry-run job never actually executes, so DepsDevBigQueryClient must never
    // call Job.waitFor() on it (that throws UnsupportedOperationException -- dry-run jobs have no
    // job to wait for, BigQuery.create(...) already returns them fully populated).
    @Test
    void dryRunValidatesTheQueryWithoutReturningRows() {
        BigQueryServiceFactory testFactory = DepsDevBigQueryClientEmulatorTest::emulatorBigQuery;
        DepsDevBigQueryClient client = new DepsDevBigQueryClient(testFactory, MAXIMUM_BYTES_BILLED);

        FetchResult result = client.fetchTopPackages(10, true);

        assertThat(result.rows()).isEmpty();
    }
}

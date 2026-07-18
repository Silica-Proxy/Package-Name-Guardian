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

import com.silicaproxy.packagenameguardian.refdatatool.DepsDevBigQueryClient.FetchResult;
import com.silicaproxy.packagenameguardian.refdatatool.DepsDevBigQueryClient.PackageRow;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/**
 * Maintainer-only CLI: runs the real, live BigQuery query and (re)writes
 * {@code src/main/resources/db/migration/R__reference_data_seed.sql} in the main
 * packagenameguardian project -- a Flyway repeatable migration Flyway re-applies automatically
 * whenever its checksum changes. Never invoked by the running application itself; only via the
 * {@code generateReferenceDataSql} Gradle task (see build.gradle) from a maintainer's machine or
 * {@code .github/workflows/refresh-reference-data.yml}, which opens a reviewed pull request with
 * the result rather than publishing it anywhere unreviewed.
 *
 * <p>Usage: {@code java -cp <classpath> ReferenceDataSqlGenerator <credentialsFilePath> <outputSqlPath> [topN] [dryRun]}
 *
 * <p>{@code credentialsFilePath} may be blank, in which case Application Default Credentials are
 * used instead -- the short-lived token Workload Identity Federation (via
 * {@code google-github-actions/auth} in {@code refresh-reference-data.yml}) or a maintainer's own
 * {@code gcloud auth application-default login} makes available -- rather than a static
 * service-account key file. See {@link ApplicationDefaultBigQueryServiceFactory}.
 *
 * <p>With {@code dryRun=true}, the real query runs through BigQuery's dry-run mode against the
 * real dataset -- validating credentials, query text and parameter binding -- without executing
 * or billing the ~417 GB scan; no output file is written.
 */
@NullMarked
public final class ReferenceDataSqlGenerator {

    private static final int MAX_BATCH_SIZE = 500;
    private static final int DEFAULT_TOP_N = 10000;
    // 1 TB: measured real cost of the partition-pruned query (Dependents filtered to a single day
    // via a resolved literal snapshot timestamp, see DepsDevBigQueryClient) is ~417 GB; this gives
    // headroom for the dataset growing before it needs raising again.
    private static final long MAXIMUM_BYTES_BILLED = 1_000_000_000_000L;

    private ReferenceDataSqlGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 4) {
            System.err.println(
                    "Usage: ReferenceDataSqlGenerator <credentialsFilePath> <outputSqlPath> [topN] [dryRun]");
            System.exit(1);
            return;
        }
        String credentialsFilePath = args[0];
        Path outputPath = Path.of(args[1]);
        int topN = args.length >= 3 ? Integer.parseInt(args[2]) : DEFAULT_TOP_N;
        boolean dryRun = args.length == 4 && Boolean.parseBoolean(args[3]);

        BigQueryServiceFactory serviceFactory = credentialsFilePath.isBlank()
                ? new ApplicationDefaultBigQueryServiceFactory()
                : new CredentialsFileBigQueryServiceFactory(credentialsFilePath);
        DepsDevBigQueryClient client = new DepsDevBigQueryClient(serviceFactory, MAXIMUM_BYTES_BILLED);
        FetchResult result = client.fetchTopPackages(topN, dryRun);

        if (dryRun) {
            System.out.printf(
                    "DRY RUN: query validated against BigQuery, %d bytes would be processed "
                            + "(nothing executed, nothing billed, %s not written)%n",
                    result.bytesProcessed(), outputPath);
            return;
        }

        String sql = buildSql(result.rows(), Instant.now());
        Files.writeString(outputPath, sql, StandardCharsets.UTF_8);

        System.out.printf(
                "Wrote %d rows (%d bytes processed by BigQuery) to %s%n",
                result.rows().size(), result.bytesProcessed(), outputPath);
    }

    // Package-private (not private) so ReferenceDataSqlGeneratorTest can feed it canned rows
    // without a real BigQuery call.
    static String buildSql(List<PackageRow> rows, Instant generatedAt) {
        StringBuilder sql = new StringBuilder();
        sql.append("-- Repeatable migration: reference_package seed data.\n");
        sql.append("-- Regenerated via ./gradlew :refdata-tool:generateReferenceDataSql from a live\n");
        sql.append("-- BigQuery query against bigquery-public-data.deps_dev_v1. Flyway re-applies this\n");
        sql.append("-- file automatically whenever its checksum changes -- no version bump needed.\n");
        sql.append("-- Generated at: ").append(DateTimeFormatter.ISO_INSTANT.format(generatedAt)).append('\n');
        sql.append("-- Row counts: ").append(formatEcosystemCounts(rows)).append('\n');
        sql.append('\n');
        sql.append("DELETE FROM reference_package;\n");
        sql.append('\n');

        for (int start = 0; start < rows.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(start + MAX_BATCH_SIZE, rows.size());
            appendInsertBatch(sql, rows.subList(start, end));
        }
        return sql.toString();
    }

    private static void appendInsertBatch(StringBuilder sql, List<PackageRow> batch) {
        sql.append("INSERT INTO reference_package (ecosystem, package_name, dependent_count, popularity_rank)\n");
        sql.append("VALUES\n");
        for (int i = 0; i < batch.size(); i++) {
            PackageRow row = batch.get(i);
            if (i > 0) {
                sql.append(",\n");
            }
            sql.append("    ('").append(escape(row.ecosystem())).append("', '")
                    .append(escape(row.packageName())).append("', ")
                    .append(row.dependentCount()).append(", ")
                    .append(row.popularityRank()).append(')');
        }
        sql.append(";\n\n");
    }

    // Package names are not expected to contain a single quote, but this generator emits literal
    // SQL text (unlike JDBC bind parameters) -- doubling any embedded quote is a cheap defensive
    // measure against an unusual upstream value corrupting the generated migration file.
    private static String escape(String value) {
        return value.replace("'", "''");
    }

    private static String formatEcosystemCounts(List<PackageRow> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (PackageRow row : rows) {
            counts.merge(row.ecosystem(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }
}

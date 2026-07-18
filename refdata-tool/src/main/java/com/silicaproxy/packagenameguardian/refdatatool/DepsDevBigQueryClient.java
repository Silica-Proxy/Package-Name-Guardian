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

import com.google.cloud.RetryOption;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics.QueryStatistics;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Only class in this subproject importing {@code com.google.cloud.bigquery.*}. Runs 2 query jobs
 * per invocation against the public {@code bigquery-public-data.deps_dev_v1} dataset: a tiny
 * lookup of the latest snapshot timestamp, then the main ranking query against {@code Dependents}
 * (the base table, not the {@code DependentsLatest} view) across all 3 ecosystems.
 *
 * <p>{@code Dependents} is partitioned by DAY on {@code SnapshotAt} (169 partitions observed) and
 * clustered on {@code System, Name, Version} -- but {@code DependentsLatest} filters with
 * {@code SnapshotAt = (SELECT MAX(Time) FROM Snapshots)}, a dynamic subquery the planner can't
 * prune partitions against, so querying it scans (close to) the whole 169-day history: ~49 TB
 * observed. Resolving the timestamp first and passing it as a genuine bound parameter into a
 * query against {@code Dependents} directly lets BigQuery prune to a single day's partition:
 * ~417 GB observed for the same 3-ecosystem result, a ~118x reduction.
 *
 * <p>Only ever invoked from {@link ReferenceDataSqlGenerator}'s {@code main}, never from a running
 * server -- this whole subproject exists so BigQuery is never on the main packagenameguardian
 * app's classpath.
 */
@NullMarked
public class DepsDevBigQueryClient {

    private static final String LATEST_SNAPSHOT_QUERY = """
            SELECT MAX(Time) AS latest_snapshot
            FROM `bigquery-public-data.deps_dev_v1.Snapshots`
            """;

    private static final String QUERY = """
            WITH ranked AS (
              SELECT System AS system, Name AS package_name,
                     COUNT(DISTINCT Dependent.Name) AS dependent_count
              FROM `bigquery-public-data.deps_dev_v1.Dependents`
              WHERE SnapshotAt = @snapshotAt
                AND System IN ('NPM', 'MAVEN', 'PYPI')
                AND DependentIsHighestReleaseWithResolution = TRUE
              GROUP BY System, Name
            )
            SELECT system, package_name, dependent_count,
                   ROW_NUMBER() OVER (PARTITION BY system ORDER BY dependent_count DESC, package_name) AS rank
            FROM ranked
            QUALIFY rank <= @topN
            ORDER BY system, rank
            """;

    private final BigQueryServiceFactory bigQueryServiceFactory;
    private final long maximumBytesBilled;

    public DepsDevBigQueryClient(BigQueryServiceFactory bigQueryServiceFactory, long maximumBytesBilled) {
        this.bigQueryServiceFactory = bigQueryServiceFactory;
        this.maximumBytesBilled = maximumBytesBilled;
    }

    public FetchResult fetchTopPackages(int topN) {
        return fetchTopPackages(topN, false);
    }

    /**
     * @param dryRun when {@code true}, the ranking query is validated and its cost estimated via
     *     BigQuery's dry-run mode -- nothing is executed or billed, and the returned {@link
     *     FetchResult} has no rows, only the estimated {@code bytesProcessed}. Lets a maintainer
     *     exercise the real query text/parameter binding/credentials against the real dataset
     *     without paying for the ~417 GB scan.
     */
    public FetchResult fetchTopPackages(int topN, boolean dryRun) {
        BigQuery bigQuery = bigQueryServiceFactory.create();
        long latestSnapshot = fetchLatestSnapshotTimestamp(bigQuery);

        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(QUERY)
                .addNamedParameter("snapshotAt", QueryParameterValue.timestamp(Long.valueOf(latestSnapshot)))
                .addNamedParameter("topN", QueryParameterValue.int64(topN))
                .setMaximumBytesBilled(maximumBytesBilled)
                .setDryRun(dryRun)
                .build();
        Job job = runQuery(bigQuery, queryConfig);

        long bytesProcessed = job.getStatistics() instanceof QueryStatistics queryStats
                ? queryStats.getTotalBytesProcessed()
                : 0L;
        if (dryRun) {
            return new FetchResult(List.of(), bytesProcessed);
        }

        TableResult result = queryResults(job);
        List<PackageRow> rows = new ArrayList<>();
        for (FieldValueList row : result.iterateAll()) {
            rows.add(new PackageRow(
                    row.get("system").getStringValue(),
                    row.get("package_name").getStringValue(),
                    row.get("dependent_count").getLongValue(),
                    (int) row.get("rank").getLongValue()));
        }
        return new FetchResult(rows, bytesProcessed);
    }

    private long fetchLatestSnapshotTimestamp(BigQuery bigQuery) {
        QueryJobConfiguration snapshotQuery = QueryJobConfiguration.newBuilder(LATEST_SNAPSHOT_QUERY)
                .setMaximumBytesBilled(maximumBytesBilled)
                .build();
        Job job = runQuery(bigQuery, snapshotQuery);
        TableResult result = queryResults(job);
        Iterator<FieldValueList> it = result.iterateAll().iterator();
        if (!it.hasNext()) {
            throw new IllegalStateException(
                    "Snapshots table returned no rows -- cannot resolve latest snapshot");
        }
        com.google.cloud.bigquery.FieldValue latestSnapshot = it.next().get("latest_snapshot");
        if (latestSnapshot.isNull()) {
            throw new IllegalStateException(
                    "Snapshots table returned a NULL latest snapshot -- cannot resolve latest snapshot");
        }
        return latestSnapshot.getTimestampValue();
    }

    private Job runQuery(BigQuery bigQuery, QueryJobConfiguration queryConfig) {
        JobId jobId = JobId.of(UUID.randomUUID().toString());
        Job job = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());
        if (Boolean.TRUE.equals(queryConfig.dryRun())) {
            // A dry run never creates an actual job to wait on -- create(...) already returns the
            // fully populated statistics (bytes processed) synchronously, so waitFor() would throw
            // UnsupportedOperationException.
            return job;
        }
        try {
            job = job.waitFor(RetryOption.totalTimeoutDuration(Duration.ofMinutes(10)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for BigQuery job " + jobId, e);
        }
        if (job == null) {
            throw new IllegalStateException("BigQuery job " + jobId + " no longer exists");
        }
        if (job.getStatus().getError() != null) {
            throw new IllegalStateException("BigQuery job " + jobId + " failed: " + job.getStatus().getError());
        }
        return job;
    }

    private static TableResult queryResults(Job job) {
        try {
            return job.getQueryResults();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while fetching results of BigQuery job " + job.getJobId(), e);
        }
    }

    public record PackageRow(String ecosystem, String packageName, long dependentCount, int popularityRank) {
    }

    public record FetchResult(List<PackageRow> rows, long bytesProcessed) {

        public FetchResult {
            rows = List.copyOf(rows);
        }
    }
}

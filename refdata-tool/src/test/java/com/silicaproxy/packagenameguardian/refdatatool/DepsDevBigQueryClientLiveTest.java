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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.silicaproxy.packagenameguardian.refdatatool.DepsDevBigQueryClient.FetchResult;
import com.silicaproxy.packagenameguardian.refdatatool.DepsDevBigQueryClient.PackageRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in smoke test against the real public {@code bigquery-public-data.deps_dev_v1} dataset,
 * excluded from the default {@code test} task (see {@code bigQueryLiveTest} in build.gradle) and
 * run only via a real GCP service account with {@code roles/bigquery.jobUser} -- mirrors proxy's
 * {@code artifact-repository}-tag pattern for tests needing real external credentials.
 */
@Tag("bigquery-live")
class DepsDevBigQueryClientLiveTest {

    private static final String CREDENTIALS_ENV_VAR = "PACKAGENAMEGUARDIAN_BIGQUERY_CREDENTIALS_FILE_PATH";
    private static final long MAXIMUM_BYTES_BILLED = 1_099_511_627_776L;
    private static final int TOP_N = 10000;

    @Test
    void fetchesRoughlyTenThousandPackagesPerEcosystem() {
        String credentialsPath = System.getenv(CREDENTIALS_ENV_VAR);
        assumeTrue(credentialsPath != null && Files.exists(Path.of(credentialsPath)),
                "Set " + CREDENTIALS_ENV_VAR + " to a real GCP service-account key file to run this test.");

        // 1 TiB cap here, not the tool's smaller default: this test deliberately runs the real,
        // full-cost query and must not be rejected by maximumBytesBilled.
        DepsDevBigQueryClient client = new DepsDevBigQueryClient(
                new CredentialsFileBigQueryServiceFactory(credentialsPath), MAXIMUM_BYTES_BILLED);
        FetchResult result = client.fetchTopPackages(TOP_N);

        assertThat(result.bytesProcessed()).isPositive();
        Map<String, List<PackageRow>> byEcosystem = result.rows().stream()
                .collect(Collectors.groupingBy(PackageRow::ecosystem));
        for (String ecosystem : List.of("NPM", "PYPI", "MAVEN")) {
            assertThat(byEcosystem.getOrDefault(ecosystem, List.of()).size())
                    .as("row count for %s", ecosystem)
                    .isGreaterThan(9000);
        }
    }
}

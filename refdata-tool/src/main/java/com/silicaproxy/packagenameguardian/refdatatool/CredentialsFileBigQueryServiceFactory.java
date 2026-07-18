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

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

/**
 * Production {@link BigQueryServiceFactory}: loads real GCP credentials from the configured key
 * file. Only invoked lazily, from inside {@code DepsDevBigQueryClient.fetchTopPackages}, not at
 * construction time -- the key file may not exist yet in every environment, and a missing/broken
 * credential must surface as a failed CLI run, not an earlier crash.
 */
@NullMarked
public class CredentialsFileBigQueryServiceFactory implements BigQueryServiceFactory {

    // Well-known convention already used by other GCP client emulators (e.g. Firestore's
    // FIRESTORE_EMULATOR_HOST): when set, redirect to a local BigQuery emulator instead of real
    // GCP. This is the only seam available to point ReferenceDataSqlGenerator's main() -- run in
    // a forked JVM via the generateReferenceDataSql JavaExec task, with no dependency-injection
    // seam across that process boundary -- at a Testcontainers BigQueryEmulatorContainer from a
    // Gradle TestKit functional test. Never set outside tests; real deployments never touch this.
    private static final String EMULATOR_HOST_ENV_VAR = "BIGQUERY_EMULATOR_HOST";
    // Matches DepsDevBigQueryClient's real query, which hardcodes this project id (the public
    // dataset's real project) -- the emulator must be seeded under the same project id.
    private static final String EMULATOR_PROJECT_ID = "bigquery-public-data";

    private final String credentialsFilePath;

    public CredentialsFileBigQueryServiceFactory(String credentialsFilePath) {
        this.credentialsFilePath = credentialsFilePath;
    }

    // credentialsFilePath is a maintainer-supplied CLI argument, never derived from untrusted
    // input -- resolved to its real, absolute form up front so the subsequent read is against a
    // concrete, already-validated filesystem location.
    @Override
    public BigQuery create() {
        String emulatorHost = System.getenv(EMULATOR_HOST_ENV_VAR);
        if (emulatorHost != null && !emulatorHost.isBlank()) {
            return emulatorBigQuery(emulatorHost);
        }

        Path resolvedPath = Path.of(credentialsFilePath).toAbsolutePath().normalize();
        try (InputStream credentialsStream = Files.newInputStream(resolvedPath)) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream);
            return BigQueryOptions.newBuilder().setCredentials(credentials).build().getService();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Unable to load BigQuery credentials from " + resolvedPath, e);
        }
    }

    private static BigQuery emulatorBigQuery(String emulatorHost) {
        return BigQueryOptions.newBuilder()
                .setHost(emulatorHost)
                .setProjectId(EMULATOR_PROJECT_ID)
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
    }
}

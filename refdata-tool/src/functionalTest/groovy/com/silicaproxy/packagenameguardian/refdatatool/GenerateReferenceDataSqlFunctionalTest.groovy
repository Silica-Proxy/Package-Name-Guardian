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

package com.silicaproxy.packagenameguardian.refdatatool

import com.google.cloud.NoCredentials
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.DatasetInfo
import com.google.cloud.bigquery.Field
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.LegacySQLTypeName
import com.google.cloud.bigquery.Schema
import com.google.cloud.bigquery.StandardTableDefinition
import com.google.cloud.bigquery.TableId
import com.google.cloud.bigquery.TableInfo
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.testcontainers.gcloud.BigQueryEmulatorContainer
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.time.Instant

/**
 * Gradle TestKit functional test: runs the real {@code :refdata-tool:generateReferenceDataSql}
 * task end to end (argument wiring, JavaExec invocation, output file contents) against a
 * Testcontainers BigQuery emulator instead of real GCP -- distinct from
 * {@code DepsDevBigQueryClientEmulatorTest}, which exercises the query logic at the plain
 * Java-class level with no Gradle build involved at all.
 *
 * <p>The emulator is reached via the {@code BIGQUERY_EMULATOR_HOST} environment variable (see
 * {@link CredentialsFileBigQueryServiceFactory}), which must actually propagate into the
 * JavaExec-forked child JVM the task spawns. {@code GradleRunner} uses the Tooling API, not a raw
 * CLI invocation -- {@code --no-daemon} isn't a supported build argument there (TestKit manages
 * daemon lifecycle itself, and correctly starts a fresh daemon whenever {@code withEnvironment}
 * differs from a previously cached one, so the environment variable does reach the nested build).
 */
class GenerateReferenceDataSqlFunctionalTest extends Specification {

    @Shared
    BigQueryEmulatorContainer emulator = new BigQueryEmulatorContainer("ghcr.io/goccy/bigquery-emulator:latest")
            .withCommand("--project", "bigquery-public-data")

    @TempDir
    Path tempDir

    def setupSpec() {
        emulator.start()
        seedEmulator()
    }

    def cleanupSpec() {
        emulator.stop()
    }

    def "generateReferenceDataSql writes a SQL seed file ranking packages from the (emulated) BigQuery query"() {
        given: "the real root project, invoked exactly as a maintainer or CI would"
        File rootProjectDir = new File(System.getProperty("testkit.rootProjectDir"))
        File outputFile = tempDir.resolve("R__reference_data_seed.sql").toFile()

        when:
        def result = GradleRunner.create()
                .withProjectDir(rootProjectDir)
                .withArguments(
                        "--stacktrace",
                        ":refdata-tool:generateReferenceDataSql",
                        "-PcredentialsPath=unused-in-emulator-mode.json",
                        "-PoutputFile=${outputFile.absolutePath}".toString(),
                        "-PtopN=10")
                .withEnvironment(System.getenv() + [BIGQUERY_EMULATOR_HOST: emulator.emulatorHttpEndpoint])
                .forwardOutput()
                .build()

        then: "the task ran successfully and produced the file"
        result.task(":refdata-tool:generateReferenceDataSql").outcome == TaskOutcome.SUCCESS
        outputFile.exists()

        and: "the SQL deletes the previous seed before inserting the new one"
        String sql = outputFile.text
        sql.indexOf("DELETE FROM reference_package;") < sql.indexOf("INSERT INTO reference_package")

        and: "both seeded npm packages are present, correctly ranked by dependent count"
        sql.contains("('NPM', 'popular', 2, 1)")
        sql.contains("('NPM', 'obscure', 1, 2)")
    }

    // 2 distinct dependents for "popular", 1 for "obscure" -- so "popular" must rank #1. A row
    // from an older snapshot must never be counted (mirrors DepsDevBigQueryClientEmulatorTest's
    // fixture, kept in sync deliberately rather than shared, since the two tests exercise
    // different layers and shouldn't be coupled by a shared fixture class).
    private void seedEmulator() {
        BigQuery bigQuery = emulatorBigQuery()

        bigQuery.create(DatasetInfo.newBuilder("deps_dev_v1").build())

        bigQuery.create(TableInfo.newBuilder(
                TableId.of("deps_dev_v1", "Snapshots"),
                StandardTableDefinition.of(Schema.of(Field.of("Time", LegacySQLTypeName.TIMESTAMP))))
                .build())

        bigQuery.create(TableInfo.newBuilder(
                TableId.of("deps_dev_v1", "Dependents"),
                StandardTableDefinition.of(Schema.of(
                        Field.of("System", LegacySQLTypeName.STRING),
                        Field.of("Name", LegacySQLTypeName.STRING),
                        Field.of("Dependent", LegacySQLTypeName.RECORD, Field.of("Name", LegacySQLTypeName.STRING)),
                        Field.of("DependentIsHighestReleaseWithResolution", LegacySQLTypeName.BOOLEAN),
                        Field.of("SnapshotAt", LegacySQLTypeName.TIMESTAMP))))
                .build())

        Instant snapshotAt = Instant.parse("2026-07-13T00:00:00Z")
        bigQuery.insertAll(InsertAllRequest.newBuilder(TableId.of("deps_dev_v1", "Snapshots"))
                .addRow(Map.of("Time", snapshotAt.toString()))
                .build())

        InsertAllRequest.Builder dependents = InsertAllRequest.newBuilder(TableId.of("deps_dev_v1", "Dependents"))
        dependents.addRow(dependentRow("NPM", "popular", "app-one", snapshotAt))
        dependents.addRow(dependentRow("NPM", "popular", "app-two", snapshotAt))
        dependents.addRow(dependentRow("NPM", "obscure", "app-one", snapshotAt))
        dependents.addRow(dependentRow("NPM", "popular", "app-three", snapshotAt.minusSeconds(86400)))
        bigQuery.insertAll(dependents.build())
    }

    private static Map<String, Object> dependentRow(String system, String name, String dependentName, Instant snapshotAt) {
        [
                System                                 : system,
                Name                                    : name,
                Dependent                               : [Name: dependentName],
                DependentIsHighestReleaseWithResolution: true,
                SnapshotAt                              : snapshotAt.toString(),
        ]
    }

    private BigQuery emulatorBigQuery() {
        BigQueryOptions.newBuilder()
                .setHost(emulator.emulatorHttpEndpoint)
                .setProjectId("bigquery-public-data")
                .setCredentials(NoCredentials.instance)
                .build()
                .service
    }
}

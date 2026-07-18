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


package com.silicaproxy.packagenameguardian.service.similarity;

import static org.assertj.core.api.Assertions.assertThat;

import com.silicaproxy.packagenameguardian.model.entity.ReferencePackage;
import com.silicaproxy.packagenameguardian.service.similarity.SimilarityScanner.ScanResult;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceSnapshot;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceSnapshot.EcosystemSnapshot;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Validates {@link SimilarityScanner} against a real deps.dev snapshot (top 10,000 packages per
 * ecosystem, {@code fixtures/deps-dev-top-packages.csv}, exported from BigQuery on 2026-07-13)
 * instead of a handful of curated names: real legitimately-similar-but-safe pairs like
 * react/react-dom, and the length-prefilter's stated performance ("low milliseconds") at real
 * candidate volume.
 */
class RealDataSimilarityScannerTest {

    private static final SimilarityScanner SCANNER = new SimilarityScanner();
    private static final PackageNameNormalizer NORMALIZER = new PackageNameNormalizer();
    private static final PackageNamespaceExtractor NAMESPACE_EXTRACTOR = new PackageNamespaceExtractor();

    private static Map<String, List<ReferencePackage>> rowsByEcosystem;

    @BeforeAll
    static void loadRealDataset() {
        rowsByEcosystem = new HashMap<>();
        try (InputStream is = RealDataSimilarityScannerTest.class.getClassLoader()
                        .getResourceAsStream("fixtures/deps-dev-top-packages.csv")) {
            if (is == null) {
                throw new IllegalStateException("Fixture not found: fixtures/deps-dev-top-packages.csv");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                reader.readLine(); // header: system,package_name,dependent_count,rank
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split(",", -1);
                    String ecosystem = fields[0];
                    String packageName = fields[1];
                    long dependentCount = Long.parseLong(fields[2]);
                    int rank = Integer.parseInt(fields[3]);
                    rowsByEcosystem
                            .computeIfAbsent(ecosystem, k -> new ArrayList<>())
                            .add(new ReferencePackage(ecosystem, packageName, dependentCount, rank));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private EcosystemSnapshot snapshotExcluding(String bigQueryEcosystem, String excludedPackageName) {
        List<ReferencePackage> rows = rowsByEcosystem.get(bigQueryEcosystem).stream()
                .filter(row -> !row.packageName().equals(excludedPackageName))
                .collect(Collectors.toList());
        return ReferenceSnapshot.from(rows, NORMALIZER, NAMESPACE_EXTRACTOR)
                .ecosystem(bigQueryEcosystem.toLowerCase(Locale.ROOT));
    }

    // Each of these is a real, currently-popular package confirmed present in the fixture,
    // legitimately similar to another real popular package in the same ecosystem -- excluded from
    // its own snapshot copy so the exact-match short-circuit can't trivially hide a real
    // collision. This exercises SimilarityScanner alone (no namespace exemption -- that's a
    // separate policy layer in PackageCheckService, covered by its own test).
    @ParameterizedTest
    @CsvSource({
        "NPM, vue-router",
        "NPM, webpack-cli",
        "NPM, babel-core",
        "NPM, jest-cli",
        "NPM, express-session",
        "NPM, lodash.merge",
        "NPM, chalk-animation",
        "PYPI, requests-oauthlib",
        "PYPI, flask-sqlalchemy",
        "PYPI, botocore",
        "PYPI, click-plugins",
    })
    void legitimatelySimilarRealPackagesAreNotFlaggedAgainstTheRestOfTheRealDataset(
            String bigQueryEcosystem, String packageName) {
        String ecosystem = bigQueryEcosystem.toLowerCase(Locale.ROOT);
        EcosystemSnapshot snapshot = snapshotExcluding(bigQueryEcosystem, packageName);
        String normalized = NORMALIZER.normalize(packageName, ecosystem);

        ScanResult result = SCANNER.scan(normalized, snapshot);

        assertThat(result.flagged())
                .as("'%s' should not be flagged as a typosquat of another real top-10000 %s package (found: %s)",
                        packageName, ecosystem, result.flaggedCandidate())
                .isFalse();
    }

    // Known, accepted limitation of SimilarityScanner *alone*: these pairs are genuinely as
    // edit-distance-close as a real typosquat (react-dom/react-dnd, eslint-plugin-import/-import-x,
    // djangorestframework/django-rest-framework, org.springframework:spring-context/spring-core,
    // org.apache.commons:commons-text/commons-io each differ by only 1-2 characters). No
    // threshold can separate these from a real attack without also missing real typosquats at the
    // same distance -- documented here rather than silently dropped.
    // The Maven pairs ARE mitigated in production: PackageCheckService's separate same-namespace
    // exemption (tested in PackageCheckServiceTest) allows them since they share a groupId with an
    // already-popular package, a signal this scanner-level test deliberately doesn't use.
    @ParameterizedTest
    @CsvSource({
        "NPM, react-dom",
        "NPM, eslint-plugin-import",
        "PYPI, djangorestframework",
        "MAVEN, org.springframework:spring-context",
        "MAVEN, org.apache.commons:commons-text",
    })
    void knownFalsePositivesAtTheScannerLevelAreStillFlagged(String bigQueryEcosystem, String packageName) {
        String ecosystem = bigQueryEcosystem.toLowerCase(Locale.ROOT);
        EcosystemSnapshot snapshot = snapshotExcluding(bigQueryEcosystem, packageName);
        String normalized = NORMALIZER.normalize(packageName, ecosystem);

        ScanResult result = SCANNER.scan(normalized, snapshot);

        assertThat(result.flagged()).isTrue();
    }

    @Test
    void scanningAgainstTenThousandRealCandidatesStaysFast() {
        EcosystemSnapshot npmSnapshot = ReferenceSnapshot.from(rowsByEcosystem.get("NPM"), NORMALIZER, NAMESPACE_EXTRACTOR)
                .ecosystem("npm");
        List<String> craftedTyposquats = List.of(
                "lodahs", "expres", "reactt", "babbel", "webpck", "jestt", "chalck", "eslintt");

        // Warm up the JIT before timing so class-loading/interpretation overhead doesn't skew
        // the measured average.
        for (String candidate : craftedTyposquats) {
            SCANNER.scan(candidate, npmSnapshot);
        }

        long startNanos = System.nanoTime();
        for (String candidate : craftedTyposquats) {
            SCANNER.scan(candidate, npmSnapshot);
        }
        long averageMs = (System.nanoTime() - startNanos) / 1_000_000 / craftedTyposquats.size();

        assertThat(averageMs)
                .as("average scan time per request against 10,000 real npm candidates")
                .isLessThan(50L);
    }
}

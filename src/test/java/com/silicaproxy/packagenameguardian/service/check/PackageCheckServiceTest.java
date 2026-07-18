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


package com.silicaproxy.packagenameguardian.service.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.silicaproxy.packagenameguardian.model.dto.CheckRequest;
import com.silicaproxy.packagenameguardian.model.dto.CheckResponse;
import com.silicaproxy.packagenameguardian.model.dto.Verdict;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties.ReferenceDataProperties;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties.SecurityProperties;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties.SimilarityProperties;
import com.silicaproxy.packagenameguardian.service.similarity.PackageNameNormalizer;
import com.silicaproxy.packagenameguardian.service.similarity.PackageNamespaceExtractor;
import com.silicaproxy.packagenameguardian.service.similarity.SimilarityScanner;
import com.silicaproxy.packagenameguardian.service.similarity.SimilarityScanner.AlgorithmScores;
import com.silicaproxy.packagenameguardian.service.similarity.SimilarityScanner.ScanResult;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceDataCache;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceSnapshot;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceSnapshot.EcosystemSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackageCheckServiceTest {

    private static final PackageNameGuardianProperties PROPERTIES = new PackageNameGuardianProperties(
            new ReferenceDataProperties(5000, 1512L),
            new SecurityProperties(true, "test-api-key"),
            new SimilarityProperties(true, true));

    @Mock
    private ReferenceDataCache cache;

    @Mock
    private PackageNameNormalizer normalizer;

    @Mock
    private SimilarityScanner scanner;

    @Mock
    private PackageNamespaceExtractor namespaceExtractor;

    private PackageCheckService checkService;

    @BeforeEach
    void setUp() {
        checkService = new PackageCheckService(
                cache, normalizer, scanner, namespaceExtractor, PROPERTIES, new SimpleMeterRegistry());
    }

    @Test
    void returnsBlockedWithColdStartReasonWhenCacheIsEmpty() {
        when(cache.current()).thenReturn(null);

        CheckResponse response = checkService.check(new CheckRequest("lodahs", "1.0.0", "npm"));

        assertThat(response.verdict()).isEqualTo(Verdict.BLOCKED);
        assertThat(response.reason()).isEqualTo("Reference data not yet available");
    }

    @Test
    void returnsAllowedWhenScannerDoesNotFlagAnyCandidate() {
        ReferenceSnapshot snapshot = new ReferenceSnapshot(Map.of());
        when(cache.current()).thenReturn(snapshot);
        when(normalizer.normalize("my-package", "npm")).thenReturn("my-package");
        when(scanner.scan("my-package", snapshot.ecosystem("npm"))).thenReturn(new ScanResult(false, null, null));

        CheckResponse response = checkService.check(new CheckRequest("my-package", "1.0.0", "npm"));

        assertThat(response.verdict()).isEqualTo(Verdict.ALLOWED);
        assertThat(response.reason()).isNull();
    }

    @Test
    void returnsBlockedWithReasonWhenScannerFlagsACandidate() {
        ReferenceSnapshot snapshot = new ReferenceSnapshot(Map.of());
        when(cache.current()).thenReturn(snapshot);
        when(normalizer.normalize("lodahs", "npm")).thenReturn("lodahs");
        ScanResult flagged = new ScanResult(true, "lodash", new AlgorithmScores(1, 0.95, 0.8));
        when(scanner.scan("lodahs", snapshot.ecosystem("npm"))).thenReturn(flagged);

        CheckResponse response = checkService.check(new CheckRequest("lodahs", "1.0.0", "npm"));

        assertThat(response.verdict()).isEqualTo(Verdict.BLOCKED);
        assertThat(response.reason()).isEqualTo("'lodahs' is suspiciously similar to popular npm package 'lodash'");
    }

    @Test
    void allowsCandidateWhoseNamespaceAlreadyHasAPopularPackageWithoutEvenScanning() {
        EcosystemSnapshot mavenData = new EcosystemSnapshot(Set.of(), Collections.emptyNavigableMap(),
                Set.of("org.springframework"));
        ReferenceSnapshot snapshot = new ReferenceSnapshot(Map.of("maven", mavenData));
        when(cache.current()).thenReturn(snapshot);
        when(namespaceExtractor.extractNamespace("org.springframework:spring-newmodule", "maven"))
                .thenReturn("org.springframework");

        CheckResponse response = checkService.check(
                new CheckRequest("org.springframework:spring-newmodule", "1.0.0", "maven"));

        assertThat(response.verdict()).isEqualTo(Verdict.ALLOWED);
        assertThat(response.reason()).isNull();
    }
}

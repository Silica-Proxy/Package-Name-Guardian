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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SimilarityScannerTest {

    private static final List<String> POPULAR_NPM_PACKAGES = List.of(
            "lodash", "express", "react", "cross-env", "vue", "webpack", "chalk", "commander");

    private final SimilarityScanner scanner = new SimilarityScanner();
    private final PackageNameNormalizer normalizer = new PackageNameNormalizer();
    private final PackageNamespaceExtractor namespaceExtractor = new PackageNamespaceExtractor();

    private EcosystemSnapshot npmSnapshot(List<String> names) {
        List<ReferencePackage> rows = names.stream()
                .map(name -> new ReferencePackage("NPM", name, 1000, 1))
                .toList();
        return ReferenceSnapshot.from(rows, normalizer, namespaceExtractor).ecosystem("npm");
    }

    @Test
    void exactMatchIsNeverFlagged() {
        ScanResult result = scanner.scan("lodash", npmSnapshot(POPULAR_NPM_PACKAGES));

        assertThat(result.flagged()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "lodahs, lodash",
        "expres, express",
        "crossenv, cross-env",
    })
    void knownTyposquatPairsAreFlagged(String candidateName, String popularName) {
        ScanResult result = scanner.scan(candidateName, npmSnapshot(POPULAR_NPM_PACKAGES));

        assertThat(result.flagged()).isTrue();
        assertThat(result.flaggedCandidate()).isEqualTo(popularName);
    }

    @Test
    void dissimilarNameIsNotFlagged() {
        ScanResult result = scanner.scan("my-totally-unrelated-utility", npmSnapshot(POPULAR_NPM_PACKAGES));

        assertThat(result.flagged()).isFalse();
    }

    @Test
    void namesShorterThanThreeCharsAreNeverFlagged() {
        ScanResult result = scanner.scan("vu", npmSnapshot(POPULAR_NPM_PACKAGES));

        assertThat(result.flagged()).isFalse();
    }

    @Test
    void candidatesFarOutsideTheLengthWindowAreIgnored() {
        // "lo" + 30 unrelated chars: wildly different length from the 6-char input, so the
        // length prefilter must exclude it from being scanned at all -- if it were scanned, no
        // algorithm would flag it either (this only asserts the observable outcome, not the
        // prefilter internals).
        EcosystemSnapshot snapshot = npmSnapshot(List.of("lodash-plus-a-very-long-unrelated-suffix-name"));

        ScanResult result = scanner.scan("lodash", snapshot);

        assertThat(result.flagged()).isFalse();
    }

    @Test
    void reqeustsIsFlaggedAsTyposquatOfRequests() {
        EcosystemSnapshot snapshot = npmSnapshot(List.of("requests"));

        ScanResult result = scanner.scan("reqeusts", snapshot);

        assertThat(result.flagged()).isTrue();
        assertThat(result.flaggedCandidate()).isEqualTo("requests");
    }

    @Test
    void legitimatelySimilarPairsAreNotAutomaticallyFlagged() {
        // react / react-dom is the canonical "legitimately similar but safe" pair: a real
        // registry lookup would never even present "react-dom" itself as a check candidate for
        // "react", but the scanner alone must not flag it either.
        EcosystemSnapshot snapshot = npmSnapshot(List.of("react-dom"));

        ScanResult result = scanner.scan("react-router", snapshot);

        assertThat(result.flagged()).isFalse();
    }
}

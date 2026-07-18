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

import com.silicaproxy.packagenameguardian.config.Metrics;
import com.silicaproxy.packagenameguardian.model.dto.CheckRequest;
import com.silicaproxy.packagenameguardian.model.dto.CheckResponse;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties;
import com.silicaproxy.packagenameguardian.service.similarity.PackageNameNormalizer;
import com.silicaproxy.packagenameguardian.service.similarity.PackageNamespaceExtractor;
import com.silicaproxy.packagenameguardian.service.similarity.SimilarityScanner;
import com.silicaproxy.packagenameguardian.service.similarity.SimilarityScanner.AlgorithmScores;
import com.silicaproxy.packagenameguardian.service.similarity.SimilarityScanner.ScanResult;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceDataCache;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceSnapshot;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceSnapshot.EcosystemSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Backs {@code POST /v1/check}. {@code BLOCKED}/"reference data not yet available" is returned
 * only before the very first sync ever completes (cache still empty) -- never on a routine daily
 * re-sync, even if that sync fails, since the previous generation keeps serving.
 */
@Service
@NullMarked
public class PackageCheckService {

    private static final Logger LOG = LoggerFactory.getLogger(PackageCheckService.class);
    private static final String COLD_START_REASON = "Reference data not yet available";
    // Control characters (CR, LF, other) are replaced with underscore in logs to prevent log forging.
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private final ReferenceDataCache cache;
    private final PackageNameNormalizer normalizer;
    private final SimilarityScanner scanner;
    private final PackageNamespaceExtractor namespaceExtractor;
    private final PackageNameGuardianProperties properties;

    private final MeterRegistry meterRegistry;

    public PackageCheckService(
            ReferenceDataCache cache,
            PackageNameNormalizer normalizer,
            SimilarityScanner scanner,
            PackageNamespaceExtractor namespaceExtractor,
            PackageNameGuardianProperties properties,
            MeterRegistry meterRegistry) {
        this.cache = cache;
        this.normalizer = normalizer;
        this.scanner = scanner;
        this.namespaceExtractor = namespaceExtractor;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public CheckResponse check(CheckRequest request) {
        ReferenceSnapshot snapshot = cache.current();
        if (snapshot == null) {
            recordVerdict(request.ecosystem(), Metrics.VERDICT_BLOCKED);
            return CheckResponse.blocked(COLD_START_REASON);
        }

        String ecosystem = request.ecosystem();
        EcosystemSnapshot ecosystemData = snapshot.ecosystem(ecosystem);

        // A candidate whose namespace (Maven groupId, npm @scope) already has at least one
        // popular package can't be a typosquatter impersonating that namespace: registries
        // enforce namespace ownership, so publishing under it means the same real publisher.
        // Configurable independently per ecosystem (PyPI has no namespace concept, so it's
        // never eligible regardless of configuration).
        if (namespaceExemptionEnabled(ecosystem)) {
            String namespace = namespaceExtractor.extractNamespace(request.packageName(), ecosystem);
            if (namespace != null && ecosystemData.knownNamespaces().contains(namespace)) {
                recordVerdict(ecosystem, Metrics.VERDICT_ALLOWED);
                return CheckResponse.allowed();
            }
        }

        String normalizedName = normalizer.normalize(request.packageName(), ecosystem);
        ScanResult result = scanner.scan(normalizedName, ecosystemData);

        if (!result.flagged()) {
            recordVerdict(ecosystem, Metrics.VERDICT_ALLOWED);
            return CheckResponse.allowed();
        }

        // Algorithm-score detail is only ever logged for the winning flagged candidate, never
        // for every candidate scanned -- that would defeat the point of the length prefilter.
        AlgorithmScores scores = result.scores();
        if (LOG.isDebugEnabled() && scores != null) {
            LOG.debug(
                    "Flagged {}/{}: levenshtein={} jaroWinkler={} fuzzyScore={} candidate={}",
                    ecosystem, sanitizeForLog(request.packageName()), scores.levenshteinDistance(),
                    scores.jaroWinklerSimilarity(), scores.fuzzyScoreNormalized(),
                    result.flaggedCandidate());
        }
        recordVerdict(ecosystem, Metrics.VERDICT_BLOCKED);
        return CheckResponse.blocked("'%s' is suspiciously similar to popular %s package '%s'"
                .formatted(request.packageName(), ecosystem, result.flaggedCandidate()));
    }

    private boolean namespaceExemptionEnabled(String ecosystem) {
        return switch (ecosystem) {
            case "npm" -> properties.similarity().npmSameScopeExemptionEnabled();
            case "maven" -> properties.similarity().mavenSameGroupIdExemptionEnabled();
            default -> false;
        };
    }

    private static String sanitizeForLog(String value) {
        return CONTROL_CHARS.matcher(value).replaceAll("_");
    }

    private void recordVerdict(String ecosystem, String verdict) {
        meterRegistry.counter(Metrics.CHECK_VERDICTS_METRIC, Metrics.TAG_ECOSYSTEM, ecosystem, Metrics.TAG_VERDICT, verdict)
                .increment();
    }
}

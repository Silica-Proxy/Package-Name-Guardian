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


package com.silicaproxy.packagenameguardian.properties;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "packagenameguardian")
@NullMarked
public record PackageNameGuardianProperties(
    ReferenceDataProperties referenceData,
    SecurityProperties security,
    SimilarityProperties similarity
) {

    public record ReferenceDataProperties(
        // Health-check sanity floor: reference_package having fewer than this many rows for any
        // ecosystem is reported DOWN (protects against a silent seed-migration/permission
        // regression going unnoticed rather than a suspiciously small reference set being served).
        @DefaultValue("5000") int minAcceptableCountPerEcosystem,
        // 63 days: one missed monthly reference-data refresh tolerated before reporting DEGRADED.
        @DefaultValue("1512") long freshnessMaxAgeHours
    ) {}

    public record SecurityProperties(
        // Set to false only for local dev / tests: POST /v1/check becomes unauthenticated.
        // Proxy sends this as a decision oracle over the network, so this must stay true
        // (with a real apiKey configured) in any deployment reachable outside a trusted network.
        @DefaultValue("true") boolean enabled,
        @Nullable String apiKey
    ) {}

    public record SimilarityProperties(
        // A candidate whose npm @scope already has at least one popular package in the
        // reference set can't be a typosquatter impersonating that scope: npm enforces scope
        // ownership per organization, so publishing under it means the same real publisher.
        @DefaultValue("true") boolean npmSameScopeExemptionEnabled,
        // Same idea for Maven's groupId (from groupId:artifactId): Maven Central enforces
        // groupId ownership, so a candidate sharing a groupId with an already-popular package
        // can't be a typosquatter impersonating that groupId.
        @DefaultValue("true") boolean mavenSameGroupIdExemptionEnabled
    ) {}
}

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


package com.silicaproxy.packagenameguardian.service.sync;

import com.silicaproxy.packagenameguardian.model.entity.ReferencePackage;
import com.silicaproxy.packagenameguardian.service.similarity.PackageNameNormalizer;
import com.silicaproxy.packagenameguardian.service.similarity.PackageNamespaceExtractor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.NullMarked;

/**
 * Fully immutable, in-memory view of {@code reference_package}, built once at application startup
 * and then only ever read (never mutated) by concurrent request threads -- see
 * {@link ReferenceDataCache}. Per-ecosystem names are pre-sorted by length so
 * {@link com.silicaproxy.packagenameguardian.service.similarity.SimilarityScanner} can prune a
 * ~10,000-candidate scan down to a short length-bounded slice in O(log n).
 */
@NullMarked
public record ReferenceSnapshot(Map<String, EcosystemSnapshot> byEcosystem) {

    public ReferenceSnapshot {
        byEcosystem = Collections.unmodifiableMap(new HashMap<>(byEcosystem));
    }

    public EcosystemSnapshot ecosystem(String ecosystem) {
        EcosystemSnapshot snapshot = byEcosystem.get(ecosystem);
        return snapshot != null ? snapshot : EcosystemSnapshot.EMPTY;
    }

    public record EcosystemSnapshot(
            Set<String> exactNames, NavigableMap<Integer, List<String>> namesByLength, Set<String> knownNamespaces) {

        static final EcosystemSnapshot EMPTY =
                new EcosystemSnapshot(Set.of(), Collections.emptyNavigableMap(), Set.of());

        public EcosystemSnapshot {
            exactNames = Collections.unmodifiableSet(new HashSet<>(exactNames));
            namesByLength = Collections.unmodifiableNavigableMap(new TreeMap<>(namesByLength));
            knownNamespaces = Collections.unmodifiableSet(new HashSet<>(knownNamespaces));
        }
    }

    // PyPI names are PEP-503-normalized here (load time), matching the normalization applied to
    // every incoming /v1/check request -- so equivalent PyPI names always compare equal.
    // reference_package.ecosystem is stored uppercase (NPM/PYPI/MAVEN, matching the BigQuery
    // System column); the snapshot keys are lowercased so lookups match the inbound request's
    // lowercase "ecosystem" field.
    public static ReferenceSnapshot from(
            List<ReferencePackage> rows,
            PackageNameNormalizer normalizer,
            PackageNamespaceExtractor namespaceExtractor) {
        Map<String, List<String>> namesByEcosystem = new HashMap<>();
        for (ReferencePackage row : rows) {
            String ecosystem = row.ecosystem().toLowerCase(Locale.ROOT);
            namesByEcosystem
                    .computeIfAbsent(ecosystem, k -> new ArrayList<>())
                    .add(normalizer.normalize(row.packageName(), ecosystem));
        }

        Map<String, EcosystemSnapshot> byEcosystem = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : namesByEcosystem.entrySet()) {
            byEcosystem.put(entry.getKey(), buildEcosystemSnapshot(entry.getKey(), entry.getValue(), namespaceExtractor));
        }
        return new ReferenceSnapshot(byEcosystem);
    }

    private static EcosystemSnapshot buildEcosystemSnapshot(
            String ecosystem, List<String> names, PackageNamespaceExtractor namespaceExtractor) {
        NavigableMap<Integer, List<String>> namesByLength = new TreeMap<>();
        Set<String> knownNamespaces = new HashSet<>();
        for (String name : names) {
            namesByLength.computeIfAbsent(name.length(), k -> new ArrayList<>()).add(name);
            String namespace = namespaceExtractor.extractNamespace(name, ecosystem);
            if (namespace != null) {
                knownNamespaces.add(namespace);
            }
        }
        return new EcosystemSnapshot(new HashSet<>(names), namesByLength, knownNamespaces);
    }
}

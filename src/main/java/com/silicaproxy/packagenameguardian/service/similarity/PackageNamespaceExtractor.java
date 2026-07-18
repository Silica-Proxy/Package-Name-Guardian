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

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Extracts the registry-enforced ownership namespace from a package name, where one exists:
 * Maven's {@code groupId} (from {@code groupId:artifactId}) and npm's {@code @scope} (from
 * {@code @scope/name}). PyPI names have no such namespace concept and always resolve to
 * {@code null}. Used to exempt a candidate from typosquat flagging when its namespace already
 * has at least one popular package -- a namespace a real publisher's registry account owns,
 * which a typosquatter cannot also publish under.
 */
@Component
@NullMarked
public class PackageNamespaceExtractor {

    private static final String MAVEN_ECOSYSTEM = "maven";
    private static final String NPM_ECOSYSTEM = "npm";
    private static final String NPM_SCOPE_PREFIX = "@";

    public @Nullable String extractNamespace(String packageName, String ecosystem) {
        return switch (ecosystem) {
            case MAVEN_ECOSYSTEM -> extractMavenGroupId(packageName);
            case NPM_ECOSYSTEM -> extractNpmScope(packageName);
            default -> null;
        };
    }

    private static @Nullable String extractMavenGroupId(String packageName) {
        int colonIndex = packageName.indexOf(':');
        return colonIndex > 0 ? packageName.substring(0, colonIndex) : null;
    }

    private static @Nullable String extractNpmScope(String packageName) {
        if (!packageName.startsWith(NPM_SCOPE_PREFIX)) {
            return null;
        }
        int slashIndex = packageName.indexOf('/');
        return slashIndex > 0 ? packageName.substring(0, slashIndex) : null;
    }
}

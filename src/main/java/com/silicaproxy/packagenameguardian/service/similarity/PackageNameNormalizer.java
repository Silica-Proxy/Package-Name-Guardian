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

import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * PEP 503 normalization for PyPI package names (lowercase, collapse runs of {@code -}/{@code _}/
 * {@code .} to a single {@code -}), applied identically to the request and the reference set at
 * load time so equivalent PyPI names always compare equal. npm and Maven names are compared
 * as-is: npm already enforces lowercase names, and Maven arrives as {@code groupId:artifactId}.
 */
@Component
@NullMarked
public class PackageNameNormalizer {

    private static final String PYPI_ECOSYSTEM = "pypi";
    private static final Pattern PEP503_SEPARATOR_RUN = Pattern.compile("[-_.]+");

    public String normalize(String packageName, String ecosystem) {
        if (!PYPI_ECOSYSTEM.equals(ecosystem)) {
            return packageName;
        }
        return PEP503_SEPARATOR_RUN.matcher(packageName.toLowerCase(Locale.ROOT)).replaceAll("-");
    }
}

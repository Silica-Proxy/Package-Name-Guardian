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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PackageNameNormalizerTest {

    private final PackageNameNormalizer normalizer = new PackageNameNormalizer();

    @ParameterizedTest
    @CsvSource({
        "Django, django",
        "django_extensions, django-extensions",
        "Flask.SQLAlchemy, flask-sqlalchemy",
        "zope--interface, zope-interface",
    })
    void appliesPep503NormalizationToPypiNames(String rawName, String expectedNormalized) {
        assertThat(normalizer.normalize(rawName, "pypi")).isEqualTo(expectedNormalized);
    }

    @Test
    void leavesNpmNamesUnchanged() {
        assertThat(normalizer.normalize("Some_Weird.Name", "npm")).isEqualTo("Some_Weird.Name");
    }

    @Test
    void leavesMavenNamesUnchanged() {
        assertThat(normalizer.normalize("org.apache.commons:commons-text", "maven"))
                .isEqualTo("org.apache.commons:commons-text");
    }
}

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


package com.silicaproxy.packagenameguardian.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties.ReferenceDataProperties;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties.SecurityProperties;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties.SimilarityProperties;
import org.junit.jupiter.api.Test;

class ApiKeyValidatorTest {

    private static final ReferenceDataProperties REFERENCE_DATA = new ReferenceDataProperties(5000, 1512L);
    private static final SimilarityProperties SIMILARITY = new SimilarityProperties(true, true);

    private ApiKeyValidator validatorFor(boolean enabled, String configuredApiKey) {
        PackageNameGuardianProperties properties = new PackageNameGuardianProperties(
                REFERENCE_DATA, new SecurityProperties(enabled, configuredApiKey), SIMILARITY);
        return new ApiKeyValidator(properties);
    }

    @Test
    void authorizesAnyRequestWhenSecurityIsDisabled() {
        ApiKeyValidator validator = validatorFor(false, "");

        assertThat(validator.isAuthorized(null)).isTrue();
        assertThat(validator.isAuthorized("anything")).isTrue();
    }

    @Test
    void authorizesTheConfiguredKey() {
        ApiKeyValidator validator = validatorFor(true, "correct-key");

        assertThat(validator.isAuthorized("correct-key")).isTrue();
    }

    @Test
    void rejectsAWrongKey() {
        ApiKeyValidator validator = validatorFor(true, "correct-key");

        assertThat(validator.isAuthorized("wrong-key")).isFalse();
    }

    @Test
    void rejectsAMissingKey() {
        ApiKeyValidator validator = validatorFor(true, "correct-key");

        assertThat(validator.isAuthorized(null)).isFalse();
    }

    @Test
    void failsClosedWhenNoApiKeyIsConfigured() {
        ApiKeyValidator validator = validatorFor(true, "");

        assertThat(validator.isAuthorized("")).isFalse();
        assertThat(validator.isAuthorized("anything")).isFalse();
    }
}

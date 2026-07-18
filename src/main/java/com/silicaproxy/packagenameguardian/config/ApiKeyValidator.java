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

import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class ApiKeyValidator {

    private final PackageNameGuardianProperties properties;

    public ApiKeyValidator(PackageNameGuardianProperties properties) {
        this.properties = properties;
    }

    public boolean isAuthorized(@Nullable String providedKey) {
        if (!properties.security().enabled()) {
            return true;
        }
        return constantTimeEquals(properties.security().apiKey(), providedKey);
    }

    // Fails closed (returns false) when no key is configured: a missing secret must not
    // silently open the endpoint once the api-key check is enabled.
    private static boolean constantTimeEquals(@Nullable String expected, @Nullable String actual) {
        if (expected == null || expected.isBlank() || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}

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


package com.silicaproxy.packagenameguardian.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /v1/check}. {@code ecosystem} arrives lowercase ({@code npm}/{@code pypi}/
 * {@code maven}); a Maven {@code packageName} arrives as {@code groupId:artifactId}. {@code
 * version} is accepted but ignored -- this service only performs name-similarity checks. All
 * fields are validated: missing, blank, or malformed values result in HTTP 400 instead of 500.
 */
@NullMarked
public record CheckRequest(
        @NotBlank @Size(max = 400) String packageName,
        @Nullable String version,
        @NotBlank @Pattern(regexp = "npm|pypi|maven") String ecosystem) {
}

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


package com.silicaproxy.packagenameguardian.model.entity;

import java.time.Instant;
import org.jspecify.annotations.NullMarked;

/**
 * Row of {@code package_allowlist} -- a real package name for one ecosystem that operators have
 * confirmed is not a typosquat, even if it would otherwise be flagged by {@code SimilarityScanner}.
 * Unlike {@code reference_package}, this table is written at runtime via {@code
 * AllowlistController}. {@code packageName} is the bare name, or {@code groupId:artifactId} for
 * Maven.
 */
@NullMarked
public record AllowlistEntry(
        String ecosystem,
        String packageName,
        Instant createdAt) {
}

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

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Response body of {@code POST /v1/check}. {@code reason} is present only when {@code verdict}
 * is {@link Verdict#BLOCKED}.
 */
@NullMarked
public record CheckResponse(Verdict verdict, @Nullable String reason) {

    public static CheckResponse allowed() {
        return new CheckResponse(Verdict.ALLOWED, null);
    }

    public static CheckResponse blocked(String reason) {
        return new CheckResponse(Verdict.BLOCKED, reason);
    }
}

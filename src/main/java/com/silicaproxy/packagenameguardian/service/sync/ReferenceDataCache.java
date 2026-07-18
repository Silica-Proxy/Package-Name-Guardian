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

import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Holds the currently-served {@link ReferenceSnapshot} behind an {@link AtomicReference}: reads
 * (every {@code POST /v1/check}) never lock, and a generation swap is a single atomic reference
 * write. {@code null} exactly when no generation has ever synced successfully (fresh deployment,
 * before the first sync completes).
 */
@Component
@NullMarked
public class ReferenceDataCache {

    private final AtomicReference<ReferenceSnapshot> current = new AtomicReference<>();

    public void swap(ReferenceSnapshot snapshot) {
        current.set(snapshot);
    }

    public @Nullable ReferenceSnapshot current() {
        return current.get();
    }
}

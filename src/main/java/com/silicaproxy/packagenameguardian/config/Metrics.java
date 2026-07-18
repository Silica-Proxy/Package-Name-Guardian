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

import org.jspecify.annotations.NullMarked;

/**
 * Single source of truth for every custom Micrometer metric name and tag key/value used across
 * packagenameguardian (PackageCheckService and its tests), so a metric's spelling is never
 * duplicated at each call/assertion site.
 */
@NullMarked
public final class Metrics {

    private Metrics() {
    }

    public static final String TAG_ECOSYSTEM = "ecosystem";
    public static final String TAG_VERDICT = "verdict";

    public static final String CHECK_VERDICTS_METRIC = "packagenameguardian.check.verdicts";
    public static final String VERDICT_ALLOWED = "ALLOWED";
    public static final String VERDICT_BLOCKED = "BLOCKED";
}

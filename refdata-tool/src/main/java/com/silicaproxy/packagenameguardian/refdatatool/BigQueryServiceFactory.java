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


package com.silicaproxy.packagenameguardian.refdatatool;

import com.google.cloud.bigquery.BigQuery;
import org.jspecify.annotations.NullMarked;

/**
 * Builds the {@link BigQuery} service instance used by {@link DepsDevBigQueryClient}. Kept as its
 * own interface, separate from {@code DepsDevBigQueryClient} itself, purely so a test can override
 * this one seam (e.g. to point at a Testcontainers BigQuery emulator) without teaching the client
 * anything about tests or emulators -- see {@code DepsDevBigQueryClientEmulatorTest}.
 */
@NullMarked
public interface BigQueryServiceFactory {

    BigQuery create();
}

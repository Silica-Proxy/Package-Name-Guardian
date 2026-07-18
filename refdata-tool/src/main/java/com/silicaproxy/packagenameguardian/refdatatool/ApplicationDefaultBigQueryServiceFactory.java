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
import com.google.cloud.bigquery.BigQueryOptions;
import org.jspecify.annotations.NullMarked;

/**
 * Production {@link BigQueryServiceFactory}: defers entirely to Application Default Credentials --
 * the short-lived token {@code google-github-actions/auth} exchanges for via Workload Identity
 * Federation in CI ({@code GOOGLE_APPLICATION_CREDENTIALS} pointing at a generated, hour-lived
 * file), or a maintainer's own {@code gcloud auth application-default login} locally. No static
 * service-account key ever touches disk under this factory's control -- see
 * {@link CredentialsFileBigQueryServiceFactory} for the file-based fallback still used by opt-in
 * local/live testing.
 */
@NullMarked
public class ApplicationDefaultBigQueryServiceFactory implements BigQueryServiceFactory {

    @Override
    public BigQuery create() {
        return BigQueryOptions.getDefaultInstance().getService();
    }
}

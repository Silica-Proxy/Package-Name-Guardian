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

-- When the reference_package seed was generated from BigQuery -- the freshness health check reads
-- this instead of flyway_schema_history.installed_on, which is reset to "now" whenever the seed is
-- applied to a freshly created database, however old the data actually is. Written exclusively
-- by R__reference_data_seed.sql; the singleton key enforces at most one row.
CREATE TABLE reference_data_meta (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    generated_at TIMESTAMPTZ NOT NULL
);

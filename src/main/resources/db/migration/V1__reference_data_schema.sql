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

CREATE TABLE reference_package (
    id BIGSERIAL PRIMARY KEY,
    ecosystem VARCHAR(10) NOT NULL,        -- NPM, PYPI, MAVEN
    package_name VARCHAR(400) NOT NULL,    -- bare name, or "groupId:artifactId" for maven
    dependent_count BIGINT NOT NULL,
    popularity_rank INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_reference_package_eco_name ON reference_package (ecosystem, package_name);

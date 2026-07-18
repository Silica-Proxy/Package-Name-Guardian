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

import com.silicaproxy.packagenameguardian.dao.repository.ReferencePackageRepository;
import com.silicaproxy.packagenameguardian.model.entity.ReferencePackage;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs once at startup: loads every row of {@code reference_package} (seeded by the
 * {@code R__reference_data_seed.sql} Flyway repeatable migration) into {@link ReferenceDataCache}
 * -- the cache itself starts empty in memory on every process restart. If the table is empty (a
 * fresh deployment whose Flyway migration hasn't seeded any rows yet), {@code POST /v1/check}
 * returns {@code BLOCKED} "reference data not yet available" until the next restart.
 */
@Component
@NullMarked
public class ReferenceDataStartupLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReferenceDataStartupLoader.class);

    private final ReferencePackageRepository referencePackageRepository;
    private final ReferenceDataCache cache;
    private final ReferenceSnapshotFactory snapshotFactory;

    public ReferenceDataStartupLoader(
            ReferencePackageRepository referencePackageRepository,
            ReferenceDataCache cache,
            ReferenceSnapshotFactory snapshotFactory) {
        this.referencePackageRepository = referencePackageRepository;
        this.cache = cache;
        this.snapshotFactory = snapshotFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ReferencePackage> rows = referencePackageRepository.findAll();
        if (rows.isEmpty()) {
            LOG.warn("reference_package is empty; has the R__reference_data_seed.sql Flyway "
                    + "migration run? POST /v1/check will report BLOCKED until it does and the "
                    + "app restarts.");
            return;
        }

        cache.swap(snapshotFactory.build(rows));
        LOG.info("Loaded {} reference packages into the in-memory cache.", rows.size());
    }
}

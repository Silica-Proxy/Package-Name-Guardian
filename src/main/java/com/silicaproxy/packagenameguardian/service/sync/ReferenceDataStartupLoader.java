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

import com.silicaproxy.packagenameguardian.dao.repository.AllowlistRepository;
import com.silicaproxy.packagenameguardian.dao.repository.ReferencePackageRepository;
import com.silicaproxy.packagenameguardian.model.entity.AllowlistEntry;
import com.silicaproxy.packagenameguardian.model.entity.ReferencePackage;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Loads {@code reference_package} (seeded by the {@code R__reference_data_seed.sql} Flyway
 * repeatable migration) and {@code package_allowlist} (written at runtime via {@code
 * AllowlistController}) into {@link ReferenceDataCache} -- the cache itself starts empty in
 * memory on every process restart. Runs once immediately at startup via {@link ApplicationRunner},
 * then again on a fixed delay via {@link Scheduled} so an allowlist entry added through the admin
 * API becomes effective without a restart. If {@code reference_package} is empty (a fresh
 * deployment whose Flyway migration hasn't seeded any rows yet), {@code POST /v1/check} returns
 * {@code BLOCKED} "reference data not yet available" until a reload finds rows. A reload that
 * fails (e.g. a transient DB outage) logs and keeps serving the previous snapshot generation
 * rather than clearing the cache.
 */
@Component
@NullMarked
public class ReferenceDataStartupLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReferenceDataStartupLoader.class);

    private final ReferencePackageRepository referencePackageRepository;
    private final AllowlistRepository allowlistRepository;
    private final ReferenceDataCache cache;
    private final ReferenceSnapshotFactory snapshotFactory;

    public ReferenceDataStartupLoader(
            ReferencePackageRepository referencePackageRepository,
            AllowlistRepository allowlistRepository,
            ReferenceDataCache cache,
            ReferenceSnapshotFactory snapshotFactory) {
        this.referencePackageRepository = referencePackageRepository;
        this.allowlistRepository = allowlistRepository;
        this.cache = cache;
        this.snapshotFactory = snapshotFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    @Scheduled(fixedDelayString = "${packagenameguardian.allowlist.refresh-interval-ms:60000}")
    public void reload() {
        List<ReferencePackage> rows;
        List<AllowlistEntry> allowlistRows;
        try {
            rows = referencePackageRepository.findAll();
            allowlistRows = allowlistRepository.findAll();
        } catch (RuntimeException e) {
            LOG.error("Reference-data reload failed; keeping the previous snapshot in serving.", e);
            return;
        }

        if (rows.isEmpty()) {
            LOG.warn("reference_package is empty; has the R__reference_data_seed.sql Flyway "
                    + "migration run? POST /v1/check will report BLOCKED until it does.");
            return;
        }

        cache.swap(snapshotFactory.build(rows, allowlistRows));
        LOG.info("Loaded {} reference packages and {} allowlist entries into the in-memory cache.",
                rows.size(), allowlistRows.size());
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.silicaproxy.packagenameguardian.dao.repository.ReferencePackageRepository;
import com.silicaproxy.packagenameguardian.model.entity.ReferencePackage;
import com.silicaproxy.packagenameguardian.service.similarity.PackageNameNormalizer;
import com.silicaproxy.packagenameguardian.service.similarity.PackageNamespaceExtractor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReferenceDataStartupLoaderTest {

    @Mock
    private ReferencePackageRepository referencePackageRepository;

    private ReferenceDataCache cache;
    private ReferenceDataStartupLoader loader;

    @BeforeEach
    void setUp() {
        cache = new ReferenceDataCache();
        ReferenceSnapshotFactory snapshotFactory =
                new ReferenceSnapshotFactory(new PackageNameNormalizer(), new PackageNamespaceExtractor());
        loader = new ReferenceDataStartupLoader(referencePackageRepository, cache, snapshotFactory);
    }

    @Test
    void populatesTheCacheFromEveryRowInTheTableAtStartup() {
        when(referencePackageRepository.findAll()).thenReturn(List.of(
                new ReferencePackage("NPM", "lodash", 1000, 1),
                new ReferencePackage("PYPI", "requests", 800, 1)));

        loader.run(null);

        ReferenceSnapshot snapshot = cache.current();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.ecosystem("npm").exactNames()).contains("lodash");
        assertThat(snapshot.ecosystem("pypi").exactNames()).contains("requests");
    }

    @Test
    void leavesTheCacheEmptyWhenTheTableHasNoRowsYet() {
        when(referencePackageRepository.findAll()).thenReturn(List.of());

        loader.run(null);

        assertThat(cache.current()).isNull();
    }
}

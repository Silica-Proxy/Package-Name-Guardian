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


package com.silicaproxy.packagenameguardian.service.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.silicaproxy.packagenameguardian.dao.repository.HealthCheckRepository;
import com.silicaproxy.packagenameguardian.dao.repository.ReferencePackageRepository;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties.ReferenceDataProperties;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties.SecurityProperties;
import com.silicaproxy.packagenameguardian.properties.PackageNameGuardianProperties.SimilarityProperties;
import com.silicaproxy.packagenameguardian.service.monitoring.MonitoringService.ComponentHealth;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonitoringServiceTest {

    private static final PackageNameGuardianProperties PROPERTIES = new PackageNameGuardianProperties(
            new ReferenceDataProperties(5000, 1512L),
            new SecurityProperties(true, "test-api-key"),
            new SimilarityProperties(true, true));

    private static final Map<String, Long> HEALTHY_COUNTS = Map.of("NPM", 10000L, "PYPI", 10000L, "MAVEN", 10000L);

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private ReferencePackageRepository referencePackageRepository;

    private MonitoringService monitoringService;

    @BeforeEach
    void setUp() {
        monitoringService = new MonitoringService(healthCheckRepository, referencePackageRepository, PROPERTIES);
    }

    @Test
    void databaseHealthIsUpWhenReachable() {
        ComponentHealth health = monitoringService.databaseHealth();

        assertThat(health.status()).isEqualTo("UP");
    }

    @Test
    void databaseHealthIsDownWhenUnreachable() {
        Mockito.doThrow(new RuntimeException("connection refused"))
                .when(healthCheckRepository).isDatabaseReachable();

        ComponentHealth health = monitoringService.databaseHealth();

        assertThat(health.status()).isEqualTo("DOWN");
    }

    @Test
    void referenceDataFreshnessIsDownWhenTableIsEmpty() {
        when(referencePackageRepository.countByEcosystem()).thenReturn(Map.of());

        ComponentHealth health = monitoringService.referenceDataFreshnessHealth();

        assertThat(health.status()).isEqualTo("DOWN");
    }

    @Test
    void referenceDataFreshnessIsDownWhenOneEcosystemIsBelowTheMinimum() {
        when(referencePackageRepository.countByEcosystem())
                .thenReturn(Map.of("NPM", 10000L, "PYPI", 100L, "MAVEN", 10000L));

        ComponentHealth health = monitoringService.referenceDataFreshnessHealth();

        assertThat(health.status()).isEqualTo("DOWN");
    }

    @Test
    void referenceDataFreshnessIsDegradedWhenNoFlywayHistoryRowIsFound() {
        when(referencePackageRepository.countByEcosystem()).thenReturn(HEALTHY_COUNTS);
        when(healthCheckRepository.referenceDataSeedLastAppliedAt()).thenReturn(Optional.empty());

        ComponentHealth health = monitoringService.referenceDataFreshnessHealth();

        assertThat(health.status()).isEqualTo("DEGRADED");
    }

    @Test
    void referenceDataFreshnessIsDegradedWhenSeedIsStale() {
        when(referencePackageRepository.countByEcosystem()).thenReturn(HEALTHY_COUNTS);
        when(healthCheckRepository.referenceDataSeedLastAppliedAt())
                .thenReturn(Optional.of(Instant.now().minusSeconds(2000 * 3600L)));

        ComponentHealth health = monitoringService.referenceDataFreshnessHealth();

        assertThat(health.status()).isEqualTo("DEGRADED");
    }

    @Test
    void referenceDataFreshnessIsUpWhenCountsAreHealthyAndSeedIsRecent() {
        when(referencePackageRepository.countByEcosystem()).thenReturn(HEALTHY_COUNTS);
        when(healthCheckRepository.referenceDataSeedLastAppliedAt())
                .thenReturn(Optional.of(Instant.now().minusSeconds(3600)));

        ComponentHealth health = monitoringService.referenceDataFreshnessHealth();

        assertThat(health.status()).isEqualTo("UP");
    }
}

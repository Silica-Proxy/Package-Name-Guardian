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


package com.silicaproxy.packagenameguardian.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silicaproxy.packagenameguardian.BaseIntegrationTest;
import com.silicaproxy.packagenameguardian.model.dto.CheckRequest;
import com.silicaproxy.packagenameguardian.model.dto.CheckResponse;
import com.silicaproxy.packagenameguardian.model.dto.Verdict;
import com.silicaproxy.packagenameguardian.model.entity.ReferencePackage;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceDataCache;
import com.silicaproxy.packagenameguardian.service.sync.ReferenceSnapshotFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

class PackageCheckControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReferenceDataCache cache;

    @Autowired
    private ReferenceSnapshotFactory snapshotFactory;

    @LocalServerPort
    private int port;

    // Deliberately not the Spring-managed bean: Boot 4.1 autoconfigures a Jackson 3
    // (tools.jackson) ObjectMapper by default, while these DTOs only need default Jackson 2
    // record (de)serialization -- a plain local instance keeps this test independent of that.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // Seeds the in-memory cache directly rather than going through the real Flyway-seeded
    // reference_package table: this test exercises PackageCheckController -> PackageCheckService
    // -> SimilarityScanner over real HTTP with a small, controlled reference set, not
    // ReferenceDataStartupLoader's DB-loading behavior (covered by its own dedicated test).
    @BeforeEach
    void seedReferenceData() {
        List<ReferencePackage> rows = List.of(
                new ReferencePackage("NPM", "lodash", 1000, 1),
                new ReferencePackage("NPM", "express", 900, 2),
                new ReferencePackage("PYPI", "requests", 800, 1),
                new ReferencePackage("MAVEN", "org.apache.commons:commons-text", 600, 1));
        cache.swap(snapshotFactory.build(rows));
    }

    private CheckResponse check(CheckRequest request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/check"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), CheckResponse.class);
    }

    @Test
    void allowsExactMatchOfAPopularPackage() throws IOException, InterruptedException {
        CheckResponse response = check(new CheckRequest("lodash", "4.17.21", "npm"));

        assertThat(response.verdict()).isEqualTo(Verdict.ALLOWED);
        assertThat(response.reason()).isNull();
    }

    @Test
    void blocksACraftedTyposquat() throws IOException, InterruptedException {
        CheckResponse response = check(new CheckRequest("lodahs", "1.0.0", "npm"));

        assertThat(response.verdict()).isEqualTo(Verdict.BLOCKED);
        assertThat(response.reason()).contains("lodash");
    }

    @Test
    void allowsAnUnrelatedObscureName() throws IOException, InterruptedException {
        CheckResponse response = check(new CheckRequest("my-totally-unrelated-internal-tool", "1.0.0", "npm"));

        assertThat(response.verdict()).isEqualTo(Verdict.ALLOWED);
    }
}

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
import com.silicaproxy.packagenameguardian.model.dto.AllowlistEntryRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

class AllowlistControllerIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    // Same rationale as PackageCheckControllerIntegrationTest: a plain local ObjectMapper, not the
    // Boot-autoconfigured Jackson 3 one, since these DTOs only need default record (de)serialization.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private HttpResponse<String> post(AllowlistEntryRequest request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/allowlist"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(AllowlistEntryRequest request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/allowlist"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .method("DELETE", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> list(String ecosystem) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/allowlist?ecosystem=" + ecosystem))
                .GET()
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void addingThenListingShowsTheNewEntry() throws IOException, InterruptedException {
        HttpResponse<String> addResponse = post(new AllowlistEntryRequest("react-dnd", "npm"));
        assertThat(addResponse.statusCode()).isEqualTo(200);

        HttpResponse<String> listResponse = list("npm");
        assertThat(listResponse.statusCode()).isEqualTo(200);
        assertThat(listResponse.body()).contains("react-dnd");
    }

    @Test
    void addingTheSameEntryTwiceIsRejectedAsAConflict() throws IOException, InterruptedException {
        assertThat(post(new AllowlistEntryRequest("duplicate-pkg", "npm")).statusCode()).isEqualTo(200);

        HttpResponse<String> secondAdd = post(new AllowlistEntryRequest("duplicate-pkg", "npm"));

        assertThat(secondAdd.statusCode()).isEqualTo(409);
    }

    @Test
    void removingThenRemovingAgainReturnsNotFound() throws IOException, InterruptedException {
        assertThat(post(new AllowlistEntryRequest("removable-pkg", "npm")).statusCode()).isEqualTo(200);

        assertThat(delete(new AllowlistEntryRequest("removable-pkg", "npm")).statusCode()).isEqualTo(200);
        assertThat(delete(new AllowlistEntryRequest("removable-pkg", "npm")).statusCode()).isEqualTo(404);
    }
}

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

import com.silicaproxy.packagenameguardian.BaseIntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "packagenameguardian.security.enabled=true")
class ApiKeyScopeIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private int listAllowlist(String key) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/allowlist?ecosystem=npm"))
                .header("Authorization", "Bearer " + key)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private int check(String key) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/check"))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("{\"packageName\":\"lodash\",\"ecosystem\":\"npm\"}"))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void checkKeyIsRejectedOnAllowlist() throws IOException, InterruptedException {
        assertThat(listAllowlist("test-api-key")).isEqualTo(401);
    }

    @Test
    void adminKeyIsAcceptedOnAllowlist() throws IOException, InterruptedException {
        assertThat(listAllowlist("test-admin-api-key")).isEqualTo(200);
    }

    @Test
    void adminKeyIsRejectedOnCheck() throws IOException, InterruptedException {
        assertThat(check("test-admin-api-key")).isEqualTo(401);
    }

    @Test
    void checkKeyIsAcceptedOnCheck() throws IOException, InterruptedException {
        assertThat(check("test-api-key")).isEqualTo(200);
    }
}

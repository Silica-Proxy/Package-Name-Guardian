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

import com.silicaproxy.packagenameguardian.config.RequiresApiKey;
import com.silicaproxy.packagenameguardian.dao.repository.AllowlistRepository;
import com.silicaproxy.packagenameguardian.model.dto.AllowlistEntryRequest;
import com.silicaproxy.packagenameguardian.model.dto.AllowlistEntryResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin API for {@code package_allowlist} -- operator-confirmed real packages that should never
 * be flagged by {@code POST /v1/check}, even if they resemble a popular package. Writes go
 * straight to the database via {@link AllowlistRepository}; they only become visible to {@code
 * POST /v1/check} on the next {@code ReferenceDataStartupLoader} reload (see {@code
 * packagenameguardian.allowlist.refresh-interval-ms}), not immediately.
 */
@RestController
@RequestMapping("/v1/allowlist")
@NullMarked
public class AllowlistController {

    private final AllowlistRepository allowlistRepository;

    public AllowlistController(AllowlistRepository allowlistRepository) {
        this.allowlistRepository = allowlistRepository;
    }

    @RequiresApiKey
    @GetMapping
    public List<AllowlistEntryResponse> list(@RequestParam String ecosystem) {
        return allowlistRepository.findByEcosystem(ecosystem.toUpperCase(Locale.ROOT)).stream()
                .map(AllowlistEntryResponse::from)
                .toList();
    }

    @RequiresApiKey
    @PostMapping
    public AllowlistEntryResponse add(@Valid @RequestBody AllowlistEntryRequest request) {
        try {
            allowlistRepository.insert(request.ecosystem().toUpperCase(Locale.ROOT), request.packageName());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'%s' is already allowlisted for %s".formatted(request.packageName(), request.ecosystem()));
        }
        return allowlistRepository.findByEcosystem(request.ecosystem().toUpperCase(Locale.ROOT)).stream()
                .filter(entry -> entry.packageName().equals(request.packageName()))
                .findFirst()
                .map(AllowlistEntryResponse::from)
                .orElseThrow();
    }

    @RequiresApiKey
    @DeleteMapping
    public void remove(@Valid @RequestBody AllowlistEntryRequest request) {
        int deleted = allowlistRepository.delete(request.ecosystem().toUpperCase(Locale.ROOT), request.packageName());
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "'%s' is not allowlisted for %s".formatted(request.packageName(), request.ecosystem()));
        }
    }
}

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

import com.silicaproxy.packagenameguardian.config.LogSanitizer;
import com.silicaproxy.packagenameguardian.config.RequiresApiKey;
import com.silicaproxy.packagenameguardian.model.dto.CheckRequest;
import com.silicaproxy.packagenameguardian.model.dto.CheckResponse;
import com.silicaproxy.packagenameguardian.service.check.PackageCheckService;
import jakarta.validation.Valid;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@NullMarked
public class PackageCheckController {

    private static final Logger LOG = LoggerFactory.getLogger(PackageCheckController.class);
    private final PackageCheckService checkService;

    public PackageCheckController(PackageCheckService checkService) {
        this.checkService = checkService;
    }

    @RequiresApiKey
    @PostMapping("/check")
    public CheckResponse check(@Valid @RequestBody CheckRequest request) {
        long startTime = System.currentTimeMillis();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Received request to check package : name={}, version={}, ecosystem={}",
                    LogSanitizer.sanitize(request.packageName()), LogSanitizer.sanitize(request.version()), LogSanitizer.sanitize(request.ecosystem()));
        }
        CheckResponse checkResponse = checkService.check(request);
        long executionTimeMs = System.currentTimeMillis() - startTime;
        LOG.info("Final decision for {}/{} (ecosystem={}) : RESULT={} (Reason: {}) [Calculated in {}ms]",
                LogSanitizer.sanitize(request.packageName()), LogSanitizer.sanitize(request.version()), LogSanitizer.sanitize(request.ecosystem()),
                checkResponse.verdict(), LogSanitizer.sanitize(checkResponse.reason()), executionTimeMs);
        return checkResponse;
    }
}

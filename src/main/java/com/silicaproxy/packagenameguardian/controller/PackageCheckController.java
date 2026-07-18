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
import com.silicaproxy.packagenameguardian.model.dto.CheckRequest;
import com.silicaproxy.packagenameguardian.model.dto.CheckResponse;
import com.silicaproxy.packagenameguardian.service.check.PackageCheckService;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
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
    // Same convention as PackageCheckService.sanitizeForLog: packageName/version are unvalidated
    // user input beyond size/blank checks, so raw control characters (CR, LF, ...) could forge
    // fake log lines (FindSecBugs CRLF_INJECTION_LOGS).
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

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
                    sanitizeForLog(request.packageName()), sanitizeForLog(request.version()), sanitizeForLog(request.ecosystem()));
        }
        CheckResponse checkResponse = checkService.check(request);
        long executionTimeMs = System.currentTimeMillis() - startTime;
        LOG.info("Final decision for {}/{} (ecosystem={}) : RESULT={} (Reason: {}) [Calculated in {}ms]",
                sanitizeForLog(request.packageName()), sanitizeForLog(request.version()), sanitizeForLog(request.ecosystem()),
                checkResponse.verdict(), checkResponse.reason(), executionTimeMs);
        return checkResponse;
    }

    private static @Nullable String sanitizeForLog(@Nullable String value) {
        return value == null ? null : CONTROL_CHARS.matcher(value).replaceAll("_");
    }
}

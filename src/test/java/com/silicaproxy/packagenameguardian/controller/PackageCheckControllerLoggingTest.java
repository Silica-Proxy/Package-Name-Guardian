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
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.silicaproxy.packagenameguardian.model.dto.CheckRequest;
import com.silicaproxy.packagenameguardian.model.dto.CheckResponse;
import com.silicaproxy.packagenameguardian.service.check.PackageCheckService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class PackageCheckControllerLoggingTest {

    private final Logger controllerLogger = (Logger) LoggerFactory.getLogger(PackageCheckController.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Mock
    private PackageCheckService checkService;

    @BeforeEach
    void attachAppender() {
        controllerLogger.setLevel(Level.DEBUG);
        appender.start();
        controllerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        controllerLogger.detachAppender(appender);
        controllerLogger.setLevel(null);
    }

    @Test
    void blockedLogLineContainsNoControlCharacters() {
        CheckRequest request = new CheckRequest("lodahs", "1.0.0", "npm");
        when(checkService.check(request)).thenReturn(CheckResponse.blocked(
                "'evil\r\n2026-01-01 INFO forged line' is suspiciously similar to popular npm package 'lodash'"));

        new PackageCheckController(checkService).check(request);

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContainPattern("\\p{Cntrl}"));
        assertThat(appender.list).anySatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("evil__2026-01-01 INFO forged line"));
    }
}

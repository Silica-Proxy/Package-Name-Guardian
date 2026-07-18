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


package com.silicaproxy.packagenameguardian.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;

@ExtendWith(MockitoExtension.class)
class ApiKeyInterceptorTest {

    // A real annotation instance (retrieved via reflection on this fixture method) rather than a
    // hand-rolled proxy -- RequiresApiKey has no attributes, but this stays correct even if it
    // gains one later.
    private static final RequiresApiKey REQUIRES_API_KEY_ANNOTATION = findRequiresApiKeyAnnotation();

    @Mock
    private ApiKeyValidator apiKeyValidator;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private HandlerMethod protectedHandlerMethod;

    private ApiKeyInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new ApiKeyInterceptor(apiKeyValidator);
    }

    @Test
    void allowsANonHandlerMethodTarget() {
        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verifyNoInteractions(apiKeyValidator, response);
    }

    @Test
    void allowsAHandlerMethodNotAnnotatedWithRequiresApiKey() {
        when(protectedHandlerMethod.getMethodAnnotation(RequiresApiKey.class)).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, protectedHandlerMethod);

        assertThat(result).isTrue();
        verifyNoInteractions(apiKeyValidator, response);
    }

    @Test
    void allowsAValidBearerToken() {
        stubProtectedHandlerMethod();
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer correct-key");
        when(apiKeyValidator.isAuthorized("correct-key")).thenReturn(true);

        boolean result = interceptor.preHandle(request, response, protectedHandlerMethod);

        assertThat(result).isTrue();
        verifyNoInteractions(response);
    }

    @Test
    void rejectsAMissingAuthorizationHeader() {
        stubProtectedHandlerMethod();
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);
        when(apiKeyValidator.isAuthorized(null)).thenReturn(false);

        boolean result = interceptor.preHandle(request, response, protectedHandlerMethod);

        assertThat(result).isFalse();
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void rejectsAHeaderThatIsNotAWellFormedBearerToken() {
        stubProtectedHandlerMethod();
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("not-a-bearer-token");
        when(apiKeyValidator.isAuthorized(null)).thenReturn(false);

        boolean result = interceptor.preHandle(request, response, protectedHandlerMethod);

        assertThat(result).isFalse();
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    private void stubProtectedHandlerMethod() {
        when(protectedHandlerMethod.getMethodAnnotation(RequiresApiKey.class)).thenReturn(REQUIRES_API_KEY_ANNOTATION);
    }

    private static RequiresApiKey findRequiresApiKeyAnnotation() {
        try {
            return RequiresApiKeyFixture.class.getMethod("annotated").getAnnotation(RequiresApiKey.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class RequiresApiKeyFixture {
        @RequiresApiKey
        public void annotated() {
        }
    }
}

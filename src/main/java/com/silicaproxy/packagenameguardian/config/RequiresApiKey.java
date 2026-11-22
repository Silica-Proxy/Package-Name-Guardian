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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Marks a controller method as requiring a Bearer API key of the given Scope
// (packagenameguardian.security.api-key for CHECK, packagenameguardian.security.admin-api-key for
// ADMIN). The two keys are distinct so that a compromised proxy, which holds the CHECK key, cannot
// modify the allowlist. Read by ApiKeyInterceptor via reflection on the resolved HandlerMethod, so
// it must stay available at runtime. Proxy sends /v1/check as a decision oracle over the network,
// so these endpoints must not be open, unauthenticated ones outside a local/test profile.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresApiKey {

    Scope value();
}

/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.skylark.function;

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.rules.providers.lib.TestInfo;
import com.facebook.buck.core.starlark.compatible.BuckStarlarkFunction;
import com.google.common.collect.ImmutableMap;
import java.util.stream.Stream;

/** Container class that holds onto the built in providers that we want to expose to users */
public class SkylarkBuiltInProviders {

  private SkylarkBuiltInProviders() {}

  /** Mapping of Skylark names -> provider methods to use */
  public static ImmutableMap<String, Provider<?>> PROVIDERS = getProviders();

  private static ImmutableMap<String, Provider<?>> getProviders() {
    return Stream.of(DefaultInfo.PROVIDER, RunInfo.PROVIDER, TestInfo.PROVIDER)
        .collect(ImmutableMap.toImmutableMap(BuckStarlarkFunction::getName, provider -> provider));
  }
}

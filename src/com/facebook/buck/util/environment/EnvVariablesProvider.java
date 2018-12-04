/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.util.environment;

import com.google.common.collect.ImmutableMap;
import java.util.Map;

/** Provides access system environment variables of the current process. */
public class EnvVariablesProvider {

  @SuppressWarnings("PMD.BlacklistedSystemGetenv")
  public static ImmutableMap<String, String> getSystemEnv() {
    if (Platform.detect().getType() == PlatformType.WINDOWS) {
      return System.getenv()
          .entrySet()
          .stream()
          .collect(ImmutableMap.toImmutableMap(e -> e.getKey().toUpperCase(), Map.Entry::getValue));
    } else {
      return ImmutableMap.copyOf(System.getenv());
    }
  }
}

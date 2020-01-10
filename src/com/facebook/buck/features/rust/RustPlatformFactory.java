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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.util.ProcessExecutor;
import javax.annotation.Nullable;

/** Factory class for creating {@link RustPlatform}s from {@link BuckConfig} sections. */
@BuckStyleValue
public abstract class RustPlatformFactory {
  abstract BuckConfig getBuckConfig();

  abstract ExecutableFinder getExecutableFinder();

  /** @return a {@link RustPlatform} from the given config subsection name. */
  public UnresolvedRustPlatform getPlatform(
      String name, UnresolvedCxxPlatform cxxPlatform, @Nullable ProcessExecutor processExecutor) {
    return new ConfigBasedUnresolvedRustPlatform(
        name, getBuckConfig(), getExecutableFinder(), cxxPlatform, processExecutor);
  }
}

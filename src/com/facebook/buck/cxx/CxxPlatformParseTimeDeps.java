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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;

/** Utility to obtain platform parse time deps for C++ rules. */
public class CxxPlatformParseTimeDeps {
  /**
   * @return an {@link Iterable} with platform dependencies that need to be resolved at parse time.
   */
  public static Iterable<BuildTarget> getPlatformParseTimeDeps(
      ToolchainProvider toolchainProvider, TargetConfiguration targetConfiguration) {
    CxxPlatformsProvider cxxPlatformsProvider =
        toolchainProvider.getByName(
            CxxPlatformsProvider.DEFAULT_NAME, targetConfiguration, CxxPlatformsProvider.class);

    // Since we don't have context on the top-level rules using this C/C++ library (e.g. it may be
    // a `python_binary`), we eagerly add the deps for all possible platforms to guarantee that the
    // correct ones are included.
    return cxxPlatformsProvider.getUnresolvedCxxPlatforms().getValues().stream()
        .flatMap(p -> RichStream.from(p.getParseTimeDeps(targetConfiguration)))
        .collect(ImmutableList.toImmutableList());
  }
}

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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

/**
 * This strategy behaves as a nop / default: all debug symbols will be loaded, as if there's no
 * select debug symbol strategy.
 */
public class CxxDebugSymbolLinkStrategyAlwaysDebug implements CxxDebugSymbolLinkStrategy {
  public static final CxxDebugSymbolLinkStrategy STRATEGY =
      new CxxDebugSymbolLinkStrategyAlwaysDebug();

  @AddToRuleKey private final String debugStrategyType = "cxx-always-debug-strategy";

  @Override
  public Optional<ImmutableSet<AbsPath>> getFocusedBuildOutputPaths() {
    return Optional.empty();
  }
}

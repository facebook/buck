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
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

/**
 * {@link CxxDebugSymbolLinkStrategy} provides the information to implement focused debugging, where
 * we limit loading debug symbols to only the focused targets.
 */
public interface CxxDebugSymbolLinkStrategy extends AddsToRuleKey {

  /**
   * Acquires the build output paths for Focused targets. To be used for scrubbing linker outputs to
   * limit the debug symbols loaded.
   *
   * @return A set to focused build output paths.
   */
  Optional<ImmutableSet<AbsPath>> getFocusedBuildOutputPaths();
}

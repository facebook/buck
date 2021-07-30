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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * {@link CxxDebugSymbolLinkStrategy} provides the information to implement focused debugging, where
 * we limit loading debug symbols to only the focused targets.
 */
public interface CxxDebugSymbolLinkStrategy extends AddsToRuleKey {

  /**
   * Get the build target's deps that are included in the focused debug targets - targets that
   * should have debug symbols.
   *
   * @param target The build target that has linker outputs
   * @param graphBuilder Used to identify the build rule that fetches the focused debug targets for
   *     the given build target.
   * @return Path to a file that should hold the focused debug targets as a json list.
   */
  Optional<SourcePath> getFilteredFocusedTargets(
      BuildTarget target, ActionGraphBuilder graphBuilder);

  /**
   * Get the additional linker args to be used when focused debugging is enabled
   *
   * @param focusedTargetsPath The path to a json list of focused debug targets
   * @return A list of linker flags to be applied during linking.
   */
  ImmutableList<Arg> getFocusedDebuggingLinkerArgs(AbsPath focusedTargetsPath);
}

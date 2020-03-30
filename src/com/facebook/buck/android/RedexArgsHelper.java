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

package com.facebook.buck.android;

import com.facebook.buck.android.redex.RedexOptions;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public class RedexArgsHelper {

  static Optional<RedexOptions> getRedexOptions(
      AndroidBuckConfig androidBuckConfig,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      boolean redexRequested,
      ImmutableList<StringWithMacros> redexExtraArgs,
      Optional<SourcePath> redexConfig) {
    if (!redexRequested) {
      return Optional.empty();
    }

    Tool redexBinary =
        androidBuckConfig.getRedexTool(graphBuilder, buildTarget.getTargetConfiguration());

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            cellRoots.getCellNameResolver(),
            graphBuilder,
            MacroExpandersForAndroidRules.MACRO_EXPANDERS);
    ImmutableList<Arg> redexExtraArgsList =
        redexExtraArgs.stream()
            .map(macrosConverter::convert)
            .collect(ImmutableList.toImmutableList());

    return Optional.of(RedexOptions.of(redexBinary, redexConfig, redexExtraArgsList));
  }
}

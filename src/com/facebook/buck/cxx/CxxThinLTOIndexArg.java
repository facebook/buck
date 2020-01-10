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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.rules.args.Arg;
import java.util.function.Consumer;

/** Holds an argument specifying the location of a thinLTO index file for optimization */
public class CxxThinLTOIndexArg implements Arg {
  @AddToRuleKey private final SourcePath thinIndicesRoot;
  private final SourcePath cxxSourcePath;

  public CxxThinLTOIndexArg(SourcePath thinIndicesRoot, SourcePath cxxSourcePath) {
    this.thinIndicesRoot = thinIndicesRoot;
    this.cxxSourcePath = cxxSourcePath;
  }

  @Override
  public void appendToCommandLine(
      Consumer<String> consumer, SourcePathResolverAdapter pathResolver) {
    consumer.accept(
        String.format(
            "-fthinlto-index=%s.thinlto.bc",
            pathResolver
                .getRelativePath(thinIndicesRoot)
                .resolve(pathResolver.getRelativePath(cxxSourcePath))));
  }
}

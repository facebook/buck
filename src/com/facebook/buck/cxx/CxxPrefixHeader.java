/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DependencyAggregation;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.function.Function;

/** Represents a header file mentioned in a `prefix_header` param in a cxx library/binary rule. */
public class CxxPrefixHeader extends PreInclude {

  public static final Flavor FLAVOR = InternalFlavor.of("prefixhdr");

  CxxPrefixHeader(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> deps,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      SourcePath sourcePath) {
    super(buildTarget, projectFilesystem, deps, ruleResolver, pathResolver, ruleFinder, sourcePath);
  }

  @Override
  public CxxPrecompiledHeader getPrecompiledHeader(
      boolean canPrecompile,
      PreprocessorDelegate preprocessorDelegateForCxxRule,
      DependencyAggregation aggregatedPreprocessDepsRule,
      CxxToolFlags computedCompilerFlags,
      Function<CxxToolFlags, String> getHash,
      Function<CxxToolFlags, String> getBaseHash,
      CxxPlatform cxxPlatform,
      CxxSource.Type sourceType,
      ImmutableList<String> sourceFlags) {

    DepsBuilder depsBuilder = new DepsBuilder(ruleFinder);

    // We need the preprocessor deps for this rule, for its prefix header.
    depsBuilder.add(preprocessorDelegateForCxxRule);
    depsBuilder.add(aggregatedPreprocessDepsRule);

    // Language needs to be part of the key, PCHs built under a different language are incompatible.
    // (Replace `c++` with `cxx`; avoid default scrubbing which would make it the cryptic `c__`.)
    final String langCode = sourceType.getLanguage().replaceAll("c\\+\\+", "cxx");

    final String pchFullID =
        String.format("pch-%s-%s", langCode, getHash.apply(computedCompilerFlags));

    return requirePrecompiledHeader(
        canPrecompile,
        preprocessorDelegateForCxxRule,
        cxxPlatform,
        sourceType,
        computedCompilerFlags,
        depsBuilder,
        getBuildTarget().getUnflavoredBuildTarget(),
        ImmutableSortedSet.of(
            cxxPlatform.getFlavor(),
            InternalFlavor.of(Flavor.replaceInvalidCharacters(pchFullID))));
  }
}

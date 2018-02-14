/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.cxx.toolchain.Preprocessor;
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
import java.util.Optional;
import java.util.function.Function;

/**
 * Represents a precompilable header file, along with dependencies.
 *
 * <p>Rules which depend on this will inherit this rule's of dependencies. For example if a given
 * rule R uses a precompiled header rule P, then all of P's {@code deps} will get merged into R's
 * {@code deps} list.
 */
public class CxxPrecompiledHeaderTemplate extends PreInclude {
  CxxPrecompiledHeaderTemplate(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> deps,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      SourcePath sourcePath) {
    super(buildTarget, projectFilesystem, deps, ruleResolver, pathResolver, ruleFinder, sourcePath);
  }

  /**
   * Build a PCH rule, given a {@code cxx_precompiled_header} rule.
   *
   * <p>We'll "instantiate" this PCH from this template, using the parameters (src, dependencies)
   * from the template itself, plus the build flags that are used in the current build rule (so that
   * this instantiated version uses compatible build flags and thus the PCH is guaranteed usable
   * with this rule).
   */
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

    Preprocessor preprocessor = preprocessorDelegateForCxxRule.getPreprocessor();

    // Build compiler flags, taking from the source rule, but leaving out its deps.
    // We just need the flags pertaining to PCH compatibility: language, PIC, macros, etc.
    // and nothing related to the deps of this particular rule (hence 'getNonIncludePathFlags').
    CxxToolFlags compilerFlags =
        CxxToolFlags.concat(
            preprocessorDelegateForCxxRule.getNonIncludePathFlags(/* no pch */ Optional.empty()),
            computedCompilerFlags);

    // Now build a new pp-delegate specially for this PCH rule.
    PreprocessorDelegate preprocessorDelegate =
        buildPreprocessorDelegate(cxxPlatform, preprocessor, compilerFlags);

    // Language needs to be part of the key, PCHs built under a different language are incompatible.
    // (Replace `c++` with `cxx`; avoid default scrubbing which would make it the cryptic `c__`.)
    final String langCode = sourceType.getLanguage().replaceAll("c\\+\\+", "cxx");
    final String pchBaseID = "pch-" + langCode + "-" + getBaseHash.apply(compilerFlags);

    for (BuildRule rule : getBuildDeps()) {
      depsBuilder.add(rule);
    }

    depsBuilder.add(requireAggregatedDepsRule(cxxPlatform));
    depsBuilder.add(preprocessorDelegate);

    return requirePrecompiledHeader(
        canPrecompile,
        preprocessorDelegate,
        cxxPlatform,
        sourceType,
        compilerFlags,
        depsBuilder,
        getBuildTarget().getUnflavoredBuildTarget(),
        ImmutableSortedSet.of(
            cxxPlatform.getFlavor(),
            InternalFlavor.of(Flavor.replaceInvalidCharacters(pchBaseID))));
  }
}

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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableSortedSet;

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
      SourcePathResolver pathResolver,
      SourcePath sourcePath) {
    this(buildTarget, projectFilesystem, makeBuildRuleParams(deps), pathResolver, sourcePath);
  }

  CxxPrecompiledHeaderTemplate(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathResolver pathResolver,
      SourcePath sourcePath) {
    super(buildTarget, projectFilesystem, buildRuleParams, pathResolver, sourcePath);
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
  protected CxxPrecompiledHeader buildPrecompiledHeaderFromPreInclude(
      DepsBuilder depsBuilder,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      BuildTarget pchTarget,
      CxxSource.Type sourceType,
      CxxToolFlags baseCompilerFlags,
      // specific to prefix-header PCH building
      PreprocessorDelegate preprocessorDelegateForCxxRule,
      // specific to template-to-PCH building
      SourcePathRuleFinder ruleFinder,
      CxxToolFlags basePPFlagsNoIncludePaths) {

    Preprocessor preprocessor =
        CxxSourceTypes.getPreprocessor(cxxPlatform, sourceType).resolve(ruleResolver);

    // Build compiler flags, taking from the source rule, but leaving out its deps.
    // We just need the flags pertaining to PCH compatibility: language, PIC, macros, etc.
    // and nothing related to the deps of this particular rule (hence 'getNonIncludePathFlags').
    CxxToolFlags compilerFlags = CxxToolFlags.concat(basePPFlagsNoIncludePaths, baseCompilerFlags);

    // Now build a new pp-delegate specially for this PCH rule.
    PreprocessorDelegate preprocessorDelegate =
        buildPreprocessorDelegate(pathResolver, cxxPlatform, preprocessor, compilerFlags);

    for (BuildRule rule : getBuildDeps()) {
      depsBuilder.add(rule);
    }

    depsBuilder.add(requireAggregatedDepsRule(ruleResolver, ruleFinder, cxxPlatform));
    depsBuilder.add(preprocessorDelegate);

    return buildPrecompiledHeader(
        depsBuilder,
        ruleResolver,
        cxxPlatform,
        pchTarget,
        sourceType,
        compilerFlags,
        preprocessorDelegate);
  }
}

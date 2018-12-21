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

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.DependencyAggregation;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Represents a precompilable header file, along with dependencies.
 *
 * <p>Rules which depend on this will inherit this rule's of dependencies. For example if a given
 * rule R uses a precompiled header rule P, then all of P's {@code deps} will get merged into R's
 * {@code deps} list.
 */
public class CxxPrecompiledHeaderTemplate extends PreInclude implements AndroidPackageable {
  CxxPrecompiledHeaderTemplate(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> deps,
      SourcePath sourcePath,
      Path absoluteHeaderPath) {
    super(buildTarget, projectFilesystem, deps, sourcePath, absoluteHeaderPath);
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
      ImmutableList<String> sourceFlags,
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver) {

    DepsBuilder depsBuilder = new DepsBuilder(ruleFinder);

    Preprocessor preprocessor = preprocessorDelegateForCxxRule.getPreprocessor();

    // Build compiler flags, taking from the source rule, but leaving out its deps.
    // We just need the flags pertaining to PCH compatibility: language, PIC, macros, etc.
    // and nothing related to the deps of this particular rule (hence 'getNonIncludePathFlags').
    CxxToolFlags compilerFlags =
        CxxToolFlags.concat(
            preprocessorDelegateForCxxRule.getNonIncludePathFlags(pathResolver),
            computedCompilerFlags);

    // Now build a new pp-delegate specially for this PCH rule.
    PreprocessorDelegate preprocessorDelegate =
        buildPreprocessorDelegate(
            cxxPlatform, preprocessor, compilerFlags, graphBuilder, pathResolver);

    // Language needs to be part of the key, PCHs built under a different language are incompatible.
    // (Replace `c++` with `cxx`; avoid default scrubbing which would make it the cryptic `c__`.)
    String langCode = sourceType.getLanguage().replaceAll("c\\+\\+", "cxx");
    String pchBaseID = "pch-" + langCode + "-" + getBaseHash.apply(compilerFlags);

    for (BuildRule rule : getBuildDeps()) {
      depsBuilder.add(rule);
    }

    depsBuilder.add(requireAggregatedDepsRule(cxxPlatform, graphBuilder, ruleFinder));
    depsBuilder.add(preprocessorDelegate);

    return requirePrecompiledHeader(
        canPrecompile,
        preprocessorDelegate,
        cxxPlatform,
        sourceType,
        compilerFlags,
        depsBuilder,
        getBuildTarget()
            .withFlavors(
                cxxPlatform.getFlavor(),
                InternalFlavor.of(Flavor.replaceInvalidCharacters(pchBaseID))),
        graphBuilder);
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
    return AndroidPackageableCollector.getPackageableRules(getBuildDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addNativeLinkable(this);
  }
}

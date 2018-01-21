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
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableSortedSet;

/** Represents a header file mentioned in a `prefix_header` param in a cxx library/binary rule. */
public class CxxPrefixHeader extends PreInclude {

  public static final Flavor FLAVOR = InternalFlavor.of("prefixhdr");

  CxxPrefixHeader(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> deps,
      SourcePathResolver pathResolver,
      SourcePath sourcePath) {
    this(buildTarget, projectFilesystem, makeBuildRuleParams(deps), pathResolver, sourcePath);
  }

  CxxPrefixHeader(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathResolver pathResolver,
      SourcePath sourcePath) {
    super(buildTarget, projectFilesystem, buildRuleParams, pathResolver, sourcePath);
  }

  @Override
  protected CxxPrecompiledHeader buildPrecompiledHeaderFromPreInclude(
      DepsBuilder depsBuilder,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      BuildTarget pchTarget,
      CxxSource.Type sourceType,
      CxxToolFlags compilerFlags,
      // specific to prefix-header PCH building
      PreprocessorDelegate preprocessorDelegateForCxxRule,
      // specific to template-to-PCH building
      SourcePathRuleFinder ruleFinder,
      CxxToolFlags basePPFlagsNoIncludePaths) {

    // We need the preprocessor deps for this rule, for its prefix header.
    depsBuilder.add(preprocessorDelegateForCxxRule);

    return buildPrecompiledHeader(
        depsBuilder,
        ruleResolver,
        cxxPlatform,
        pchTarget,
        sourceType,
        compilerFlags,
        preprocessorDelegateForCxxRule);
  }
}

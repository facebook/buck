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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
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
}

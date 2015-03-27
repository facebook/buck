/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.python.ImmutablePythonPackageComponents;
import com.facebook.buck.python.PythonPackagable;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

public class CxxPythonExtension extends NoopBuildRule implements PythonPackagable {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final Path module;

  public CxxPythonExtension(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      Path module) {
    super(params, pathResolver);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.module = module;
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents(CxxPlatform cxxPlatform) {
    BuildRule extension =
        CxxDescriptionEnhancer.requireBuildRule(
            params,
            ruleResolver,
            cxxPlatform.getFlavor(),
            CxxDescriptionEnhancer.SHARED_FLAVOR);
    SourcePath output = new BuildTargetSourcePath(
        extension.getProjectFilesystem(),
        extension.getBuildTarget());
    return ImmutablePythonPackageComponents.of(
        ImmutableMap.of(module, output),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of());
  }

}

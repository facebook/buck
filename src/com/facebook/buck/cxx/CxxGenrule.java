/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.shell.Genrule;

/** Genrule with C++ aware macros. */
public class CxxGenrule extends NoopBuildRuleWithDeclaredAndExtraDeps implements HasOutputName {

  private final String output;

  public CxxGenrule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      String output) {
    super(buildTarget, projectFilesystem, params);
    this.output = output;
  }

  @Override
  public String getOutputName() {
    return output;
  }

  /** Get the genrule */
  public SourcePath getGenrule(CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    Genrule rule =
        (Genrule)
            ruleResolver.requireRule(getBuildTarget().withAppendedFlavors(cxxPlatform.getFlavor()));
    return rule.getSourcePathToOutput();
  }
}

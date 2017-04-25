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

import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.shell.Genrule;

public class CxxGenrule extends NoopBuildRule implements HasOutputName {

  private final BuildRuleResolver resolver;
  private final String output;

  public CxxGenrule(BuildRuleParams params, BuildRuleResolver resolver, String output) {
    super(params);
    this.resolver = resolver;
    this.output = output;
  }

  @Override
  public String getOutputName() {
    return output;
  }

  public SourcePath getGenrule(CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    Genrule rule =
        (Genrule)
            resolver.requireRule(getBuildTarget().withAppendedFlavors(cxxPlatform.getFlavor()));
    return rule.getSourcePathToOutput();
  }
}

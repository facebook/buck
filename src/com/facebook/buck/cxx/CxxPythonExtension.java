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

import com.facebook.buck.python.PythonPackagable;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class CxxPythonExtension extends AbstractBuildRule implements PythonPackagable {

  private final Path module;
  private final SourcePath output;
  private final CxxLink rule;

  public CxxPythonExtension(
      BuildRuleParams params,
      Path module,
      SourcePath output,
      CxxLink rule) {
    super(params);
    this.module = Preconditions.checkNotNull(module);
    this.output = Preconditions.checkNotNull(output);
    this.rule = Preconditions.checkNotNull(rule);
  }

  @Override
  protected Iterable<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("module", module.toString());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents() {
    return new PythonPackageComponents(
        ImmutableMap.of(module, output),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of());
  }

  public Path getModule() {
    return module;
  }

  public CxxLink getRule() {
    return rule;
  }

}

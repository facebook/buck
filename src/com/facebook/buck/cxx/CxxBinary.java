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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class CxxBinary
    extends AbstractBuildRule
    implements BinaryBuildRule, NativeTestable, HasRuntimeDeps {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final Path output;
  private final CxxLink rule;
  private final Tool executable;
  private final ImmutableSortedSet<BuildTarget> tests;
  private final ImmutableSortedSet<FrameworkPath> frameworks;

  public CxxBinary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver resolver,
      Path output,
      CxxLink rule,
      Tool executable,
      Iterable<FrameworkPath> frameworks,
      Iterable<BuildTarget> tests) {
    super(params, resolver);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.output = output;
    this.rule = rule;
    this.executable = executable;
    this.tests = ImmutableSortedSet.copyOf(tests);
    this.frameworks = ImmutableSortedSet.copyOf(frameworks);
  }

  @Override
  public Tool getExecutableCommand() {
    return executable;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  public CxxLink getRule() {
    return rule;
  }

  @Override
  public boolean isTestedBy(BuildTarget testRule) {
    return tests.contains(testRule);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    return CxxPreprocessables.getCxxPreprocessorInput(
        targetGraph,
        params,
        ruleResolver,
        cxxPlatform.getFlavor(),
        headerVisibility,
        CxxPreprocessables.IncludeType.LOCAL,
        ImmutableMultimap.<CxxSource.Type, String>of(),
        cxxPlatform,
        frameworks);
  }

  // This rule just delegates to the output of the `CxxLink` rule and so needs that available at
  // runtime.  Model this via `HasRuntimeDeps`.
  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.copyOf(executable.getDeps(getResolver()));
  }

}

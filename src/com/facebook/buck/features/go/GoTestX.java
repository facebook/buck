/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.attr.HasSupplementaryOutputs;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.test.rule.CoercedTestRunnerSpec;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.TestXRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** go test that implements the test protocol */
public class GoTestX extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements TestXRule,
        HasRuntimeDeps,
        HasSupplementaryOutputs,
        ExternalTestRunnerRule,
        BinaryBuildRule {

  private final GoBinary testMain;

  private final ImmutableSet<String> labels;
  private final ImmutableSet<String> contacts;
  private final ImmutableSortedSet<SourcePath> resources;
  private final CoercedTestRunnerSpec specs;

  public GoTestX(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      GoBinary testMain,
      ImmutableSet<String> labels,
      ImmutableSet<String> contacts,
      CoercedTestRunnerSpec specs,
      ImmutableSortedSet<SourcePath> resources) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.testMain = testMain;
    this.labels = labels;
    this.contacts = contacts;
    this.specs = specs;
    this.resources = resources;
  }

  @Override
  public CoercedTestRunnerSpec getSpecs() {
    return specs;
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "__test_%s_output__");
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    SourcePath sourcePath = testMain.getSourcePathToOutput();
    if (sourcePath == null) {
      return null;
    }
    return ForwardingBuildTargetSourcePath.of(getBuildTarget(), sourcePath);
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return Stream.concat(
        Stream.of((testMain.getBuildTarget())),
        resources.stream()
            .map(buildRuleResolver::filterBuildRuleInputs)
            .flatMap(ImmutableSet::stream)
            .map(BuildRule::getBuildTarget));
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    return testMain.getExecutableCommand(OutputLabel.defaultLabel());
  }
}

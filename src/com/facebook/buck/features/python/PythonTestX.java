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

package com.facebook.buck.features.python;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.test.rule.CoercedTestRunnerSpec;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.TestXRule;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * New Python Test rule that uses the TestX protocol to run.
 *
 * <p>It cannot be run via buck's internal runners.
 */
public class PythonTestX extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements TestXRule, HasRuntimeDeps, ExternalTestRunnerRule {

  private final Supplier<? extends SortedSet<BuildRule>> originalDeclaredDeps;
  private final Supplier<? extends SortedSet<BuildRule>> originalExtraDeps;
  private final PythonBinary binary;
  private final ImmutableSet<String> labels;
  private final ImmutableSet<String> contacts;
  private final CoercedTestRunnerSpec specs;

  private PythonTestX(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Supplier<? extends SortedSet<BuildRule>> originalDeclaredDeps,
      Supplier<? extends SortedSet<BuildRule>> originalExtraDeps,
      PythonBinary binary,
      ImmutableSet<String> labels,
      ImmutableSet<String> contacts,
      CoercedTestRunnerSpec specs) {
    super(buildTarget, projectFilesystem, params);
    this.originalDeclaredDeps = originalDeclaredDeps;
    this.originalExtraDeps = originalExtraDeps;
    this.binary = binary;
    this.labels = labels;
    this.contacts = contacts;
    this.specs = specs;
  }

  public static PythonTestX from(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      PythonBinary binary,
      ImmutableSet<String> labels,
      ImmutableSet<String> contacts,
      CoercedTestRunnerSpec specs) {
    return new PythonTestX(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(binary)).withoutExtraDeps(),
        params.getDeclaredDeps(),
        params.getExtraDeps(),
        binary,
        labels,
        contacts,
        specs);
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
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    SourcePath sourcePath = binary.getSourcePathToOutput();
    if (sourcePath == null) {
      return null;
    }
    return ForwardingBuildTargetSourcePath.of(getBuildTarget(), sourcePath);
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return RichStream.from(originalDeclaredDeps.get())
        .concat(originalExtraDeps.get().stream())
        .map(BuildRule::getBuildTarget)
        .concat(binary.getRuntimeDeps(buildRuleResolver))
        .concat(
            BuildableSupport.getDeps(
                    binary.getExecutableCommand(OutputLabel.defaultLabel()), buildRuleResolver)
                .map(BuildRule::getBuildTarget));
  }

  @Override
  public CoercedTestRunnerSpec getSpecs() {
    return specs;
  }
}

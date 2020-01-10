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

package com.facebook.buck.android;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.test.rule.CoercedTestRunnerSpec;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBinary;
import com.facebook.buck.jvm.java.JavaTestX;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** The new Robolectric Test rule that uses the test protocol to run. */
public class RobolectricTestX extends JavaTestX {

  private final RobolectricTestHelper robolectricTestHelper;

  protected RobolectricTestX(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      JavaBinary compiledTestsBinary,
      JavaLibrary compiledTestsLibrary,
      Set<String> labels,
      Set<String> contacts,
      CoercedTestRunnerSpec specs,
      List<Arg> vmArgs,
      Optional<DummyRDotJava> optionalDummyRDotJava,
      Optional<SourcePath> robolectricRuntimeDependency,
      Optional<SourcePath> robolectricManifest,
      boolean passDirectoriesInFile) {
    super(
        buildTarget,
        projectFilesystem,
        params,
        compiledTestsBinary,
        compiledTestsLibrary,
        labels,
        contacts,
        specs,
        vmArgs);
    this.robolectricTestHelper =
        new RobolectricTestHelper(
            getBuildTarget(),
            optionalDummyRDotJava,
            robolectricRuntimeDependency,
            robolectricManifest,
            getProjectFilesystem(),
            passDirectoriesInFile);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    // Before tests starts, write resource and assets to directories
    ImmutableList.Builder<Step> stepBuilder = ImmutableList.builder();
    stepBuilder.addAll(super.getBuildSteps(buildContext, buildableContext));
    robolectricTestHelper.addPreTestSteps(buildContext, stepBuilder);
    return stepBuilder.build();
  }

  @Override
  protected ImmutableList<String> getJvmArgs(SourcePathResolverAdapter pathResolver) {
    ImmutableList.Builder<String> vmArgsBuilder = ImmutableList.builder();
    robolectricTestHelper.amendVmArgs(vmArgsBuilder, pathResolver);
    return vmArgsBuilder.addAll(super.getJvmArgs(pathResolver)).build();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return Stream.concat(
        // Inherit any runtime deps from `JavaTest`.
        super.getRuntimeDeps(buildRuleResolver),
        robolectricTestHelper.getExtraRuntimeDeps(buildRuleResolver, getBuildDeps()));
  }
}

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

package com.facebook.buck.rules.modern;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.external.model.ExternalAction;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.FakeTool;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.buildables.BuildableCommandExecutionStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.AbstractIsolatedExecutionStep;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class BuildableWithExternalActionTest {

  private BuildContext buildContext;
  private BuildTarget buildTarget;
  private ProjectFilesystem projectFilesystem;
  private OutputPathResolver outputPathResolver;
  private BuildCellRelativePathFactory buildCellRelativePathFactory;

  @Before
  public void setUp() {
    ActionGraphBuilder actionGraphBuilder = new TestActionGraphBuilder();
    buildContext =
        FakeBuildContext.withSourcePathResolver(actionGraphBuilder.getSourcePathResolver());
    projectFilesystem = new FakeProjectFilesystem();
    buildTarget = BuildTargetFactory.newInstance("//some:target");
    outputPathResolver = new DefaultOutputPathResolver(projectFilesystem, buildTarget);
    buildCellRelativePathFactory =
        new DefaultBuildCellRelativePathFactory(
            buildContext.getBuildCellRootPath(),
            projectFilesystem,
            Optional.of(outputPathResolver));
  }

  @Test
  public void canGetStepsWithoutBuildableCommandExecutionStep() {
    Buildable buildable = new FakeBuildable(false, "test_arg", buildTarget);
    ImmutableList<Step> steps =
        buildable.getBuildSteps(
            buildContext, projectFilesystem, outputPathResolver, buildCellRelativePathFactory);
    assertThat(steps, Matchers.contains(new FakeStep("test_arg")));
  }

  @Test
  public void canGetStepsWithBuildableCommandExecutionStep() {
    Buildable buildable = new FakeBuildable(true, "test_arg", buildTarget);

    ImmutableList<Step> steps =
        buildable.getBuildSteps(
            buildContext, projectFilesystem, outputPathResolver, buildCellRelativePathFactory);

    assertThat(steps, Matchers.hasSize(1));
    assertThat(steps.get(0), Matchers.instanceOf(BuildableCommandExecutionStep.class));

    BuildableCommandExecutionStep actualStep = (BuildableCommandExecutionStep) steps.get(0);
    String description = actualStep.getDescription(TestExecutionContext.newInstance());
    assertThat(description, Matchers.containsString("test_arg"));
  }

  private static class FakeBuildable extends BuildableWithExternalAction {

    private final String arg;

    private FakeBuildable(boolean shouldUseExternalActions, String arg, BuildTarget buildTarget) {
      super(
          shouldUseExternalActions,
          new FakeTool(),
          () ->
              ExplicitBuildTargetSourcePath.of(
                  buildTarget, RelPath.get("test_external_actions.jar")));
      this.arg = arg;
    }

    @Override
    public BuildableCommand getBuildableCommand(
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildContext buildContext) {
      return BuildableCommand.newBuilder()
          .setExternalActionClass(FakeExternalAction.class.getName())
          .addAllArgs(ImmutableList.of(arg))
          .putAllEnv(ImmutableMap.of())
          .build();
    }
  }

  private static class FakeExternalAction implements ExternalAction {

    @Override
    public ImmutableList<IsolatedStep> getSteps(BuildableCommand buildableCommand) {
      return ImmutableList.of(new FakeStep(buildableCommand.getArgs(0)));
    }
  }

  private static class FakeStep extends AbstractIsolatedExecutionStep {

    private final String arg;

    private FakeStep(String arg) {
      super("fake_step");
      this.arg = arg;
    }

    @Override
    public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context) {
      return StepExecutionResults.SUCCESS;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FakeStep fakeStep = (FakeStep) o;
      return Objects.equal(arg, fakeStep.arg);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(arg);
    }
  }
}

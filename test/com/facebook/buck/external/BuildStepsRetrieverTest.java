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

package com.facebook.buck.external;

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.RmIsolatedStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildStepsRetrieverTest {

  @Rule public final ExpectedException exception = ExpectedException.none();

  @Test
  public void canRetrieveStep() {
    ImmutableList<IsolatedStep> steps =
        BuildStepsRetriever.getStepsForBuildable(
            ExternalArgsParser.ParsedArgs.of(
                FakeWorkingExternalBuildableWithOneStep.class,
                BuildableCommand.newBuilder()
                    .addArgs("fake_path")
                    .putAllEnv(ImmutableMap.of())
                    .build()));
    assertThat(steps, Matchers.contains(RmIsolatedStep.of(RelPath.get("fake_path"))));
  }

  @Test
  public void canRetrieveSteps() {
    ImmutableList<IsolatedStep> steps =
        BuildStepsRetriever.getStepsForBuildable(
            ExternalArgsParser.ParsedArgs.of(
                FakeWorkingExternalBuildableWithMoreThanOneStep.class,
                BuildableCommand.newBuilder()
                    .addAllArgs(ImmutableList.of("dir_to_create", "path_to_remove"))
                    .putAllEnv(ImmutableMap.of())
                    .build()));
    assertThat(
        steps,
        Matchers.contains(
            MkdirIsolatedStep.of(RelPath.get("dir_to_create")),
            RmIsolatedStep.of(RelPath.get("path_to_remove"))));
  }

  @Test
  public void throwsIfNoEmptyConstructor() {
    exception.expect(IllegalStateException.class);
    exception.expectMessage("FakeNotWorkingExternalBuildable must have empty constructor");

    BuildStepsRetriever.getStepsForBuildable(
        ExternalArgsParser.ParsedArgs.of(
            FakeNotWorkingExternalBuildable.class,
            BuildableCommand.newBuilder()
                .addArgs("fake_path")
                .putAllEnv(ImmutableMap.of())
                .build()));
  }

  private static class FakeWorkingExternalBuildableWithOneStep implements ExternalAction {

    private FakeWorkingExternalBuildableWithOneStep() {}

    @Override
    public ImmutableList<IsolatedStep> getSteps(BuildableCommand buildableCommand) {
      List<String> command = buildableCommand.getArgsList();
      Preconditions.checkState(command.size() == 1, "Expected 1 arg, got %s", command.size());
      return ImmutableList.of(RmIsolatedStep.of(RelPath.get(command.get(0))));
    }
  }

  private static class FakeWorkingExternalBuildableWithMoreThanOneStep implements ExternalAction {

    private FakeWorkingExternalBuildableWithMoreThanOneStep() {}

    @Override
    public ImmutableList<IsolatedStep> getSteps(BuildableCommand buildableCommand) {
      List<String> command = buildableCommand.getArgsList();
      Preconditions.checkState(command.size() == 2, "Expected 2 arg, got %s", command.size());
      return ImmutableList.of(
          MkdirIsolatedStep.of(RelPath.get(command.get(0))),
          RmIsolatedStep.of(RelPath.get(command.get(1))));
    }
  }

  private static class FakeNotWorkingExternalBuildable implements ExternalAction {

    @SuppressWarnings("unused")
    private FakeNotWorkingExternalBuildable(String constructorParam) {}

    @Override
    public ImmutableList<IsolatedStep> getSteps(BuildableCommand buildableCommand) {
      List<String> command = buildableCommand.getArgsList();
      Preconditions.checkState(command.size() == 1, "Expected 1 arg, got %s", command.size());
      return ImmutableList.of(RmIsolatedStep.of(RelPath.get(command.get(0))));
    }
  }
}

/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.step;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.ActionExecutionContext;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionWrapperDataFactory.DeclaredArtifact;
import com.facebook.buck.core.rules.actions.Artifact;
import com.facebook.buck.core.rules.actions.Artifact.BuildArtifact;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.actions.FakeActionFactory;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionSuccess;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.BuckEventBusForTests.CapturingConsoleEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystemFactory;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.function.TriFunction;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ActionExecutionStepTest {
  @Test
  public void canExecuteAnAction()
      throws IOException, InterruptedException, ActionCreationException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path baseCell = Paths.get("cell");
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:foo");

    Path output = Paths.get("somepath");

    TriFunction<
            ImmutableSet<Artifact>,
            ImmutableSet<BuildArtifact>,
            ActionExecutionContext,
            ActionExecutionResult>
        actionFunction =
            (inputs, outputs, ctx) -> {
              assertEquals(ImmutableSet.of(), inputs);
              assertThat(outputs, Matchers.hasSize(1));
              assertEquals(
                  ExplicitBuildTargetSourcePath.of(buildTarget, output),
                  Iterables.getOnlyElement(outputs).getPath());
              assertSame(baseCell, ctx.getBuildCellRootPath());
              assertFalse(ctx.getShouldDeleteTemporaries());
              ctx.logError(new RuntimeException("message"), "my error %s", 1);
              ctx.postEvent(ConsoleEvent.info("my test info"));
              return ImmutableActionExecutionSuccess.of(
                  Optional.empty(), Optional.of("my std err"));
            };

    FakeActionFactory fakeActionFactory = new FakeActionFactory();
    DeclaredArtifact declaredArtifact = fakeActionFactory.declareArtifact(output);
    FakeAction action =
        fakeActionFactory.createFakeAction(
            buildTarget, ImmutableSet.of(), ImmutableSet.of(declaredArtifact), actionFunction);

    ActionExecutionStep step = new ActionExecutionStep(action, false, baseCell);
    BuckEventBus testEventBus = BuckEventBusForTests.newInstance();
    BuckEventBusForTests.CapturingConsoleEventListener consoleEventListener =
        new CapturingConsoleEventListener();
    testEventBus.register(consoleEventListener);
    assertEquals(
        StepExecutionResult.of(0, Optional.of("my std err")),
        step.execute(
            ExecutionContext.of(
                Console.createNullConsole(),
                testEventBus,
                Platform.UNKNOWN,
                ImmutableMap.of(),
                new FakeJavaPackageFinder(),
                ImmutableMap.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                TestCellPathResolver.get(projectFilesystem),
                baseCell,
                new FakeProcessExecutor(),
                new FakeProjectFilesystemFactory())));

    assertThat(
        consoleEventListener.getLogMessages(),
        Matchers.contains(
            Matchers.containsString(
                "my error 1" + System.lineSeparator() + "java.lang.RuntimeException: message"),
            Matchers.containsString("my test info")));
  }
}

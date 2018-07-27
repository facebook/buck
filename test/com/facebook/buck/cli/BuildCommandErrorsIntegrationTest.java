/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypes;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.immutables.value.Value;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class BuildCommandErrorsIntegrationTest {
  private static final boolean DEBUG = true;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectWorkspace workspace;
  private MockDescription mockDescription;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "errors", tmp);
    workspace.setUp();
    mockDescription = new MockDescription();
    workspace.setKnownBuildRuleTypesFactoryFactory(
        (processExecutor, pluginManager, sandboxExecutionStrategyFactory) ->
            cell -> KnownBuildRuleTypes.builder().addDescriptions(mockDescription).build());
  }

  // TODO(cjhopman): Add cases for errors in other phases of the build (watchman, parsing,
  // action graph, etc.).

  // TODO(cjhopman): Add tests for buck.log.

  private String getStderr(ProcessResult result) {
    String stderr = result.getStderr();
    if (DEBUG) {
      System.out.println("=== STDERR ===");
      System.out.print(stderr);
      System.out.println("==============");
    }
    return stderr;
  }

  @Test
  public void exceptionWithCauseThrown() throws Exception {
    mockDescription.buildRuleFactory =
        exceptionTargetFactory("failure message", RuntimeException.class, RuntimeException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            "Buck encountered an internal error",
            "java.lang.RuntimeException:  <- failure message -> ",
            "<stacktrace>",
            "Caused by: java.lang.RuntimeException: failure message",
            "<stacktrace>",
            "",
            "    When building rule //:target_name."),
        getError(getStderr(result)));
  }

  @Test
  public void exceptionWithCauseThrownInStep() throws Exception {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory(
            "failure message", RuntimeException.class, RuntimeException.class);
    workspace.runBuckBuild(":target_name");
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            "Buck encountered an internal error",
            "java.lang.RuntimeException:  <- failure message -> ",
            "<stacktrace>",
            "Caused by: java.lang.RuntimeException: failure message",
            "<stacktrace>",
            "",
            "    When running <failing_step>.",
            "    When building rule //:target_name."),
        getError(getStderr(result)));
  }

  @Test
  public void runtimeExceptionThrown() throws Exception {
    mockDescription.buildRuleFactory =
        exceptionTargetFactory("failure message", RuntimeException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            "Buck encountered an internal error",
            "java.lang.RuntimeException: failure message",
            "<stacktrace>",
            "",
            "    When building rule //:target_name."),
        getError(getStderr(result)));
  }

  @Test
  public void runtimeExceptionThrownInStep() throws Exception {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory("failure message", RuntimeException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            "Buck encountered an internal error",
            "java.lang.RuntimeException: failure message",
            "<stacktrace>",
            "",
            "    When running <failing_step>.",
            "    When building rule //:target_name."),
        getError(getStderr(result)));
  }

  @Test
  public void ioExceptionThrownInStep() throws Exception {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory("failure message", IOException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            "Buck encountered an internal error",
            "java.io.IOException: failure message",
            "<stacktrace>",
            "",
            "    When running <failing_step>.",
            "    When building rule //:target_name."),
        getError(getStderr(result)));
  }

  @Test
  public void ioExceptionThrown() throws Exception {
    mockDescription.buildRuleFactory = exceptionTargetFactory("failure message", IOException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            "Buck encountered an internal error",
            "java.io.IOException: failure message",
            "<stacktrace>",
            "",
            "    When building rule //:target_name."),
        getError(getStderr(result)));
  }

  @Test
  public void ioExceptionWithRuleInMessageThrown() throws Exception {
    mockDescription.buildRuleFactory =
        exceptionTargetFactory("failure message //:target_name", IOException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            "Buck encountered an internal error",
            "java.io.IOException: failure message //:target_name",
            "<stacktrace>",
            "",
            "    When building rule //:target_name."),
        getError(getStderr(result)));
  }

  @Test
  public void humanReadableExceptionThrownInStep() throws Exception {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory("failure message", HumanReadableException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            "Build failed: failure message",
            "    When running <failing_step>.",
            "    When building rule //:target_name."),
        getError(getStderr(result)));
  }

  @Test
  public void humanReadableExceptionThrown() throws Exception {
    mockDescription.buildRuleFactory =
        exceptionTargetFactory("failure message", HumanReadableException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertEquals(
        linesToText("Build failed: failure message", "    When building rule //:target_name."),
        getError(getStderr(result)));
  }

  @Test
  public void withStepReturningFailure() throws Exception {
    mockDescription.buildRuleFactory = exitCodeTargetFactory("failure message", 1);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            "Build failed: Command failed with exit code 1.",
            "stderr: failure message",
            "    When running <step_with_exit_code_1>.",
            "    When building rule //:target_name."),
        getError(getStderr(result)));
  }

  @Test
  public void withSuccessfulStep() throws Exception {
    mockDescription.buildRuleFactory = exitCodeTargetFactory("success message", 0);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertSuccess();
    assertEquals("", getError(getStderr(result)));
  }

  @Test
  public void successWithNoSteps() throws Exception {
    mockDescription.buildRuleFactory = successTargetFactory();
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertSuccess();
    assertEquals("", getError(getStderr(result)));
  }

  @Test
  public void runtimeExceptionThrownKeepGoing() throws Exception {
    mockDescription.buildRuleFactory =
        exceptionTargetFactory("failure message", RuntimeException.class);
    ProcessResult result = workspace.runBuckBuild("--keep-going", ":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            " ** Summary of failures encountered during the build **",
            "Rule //:target_name FAILED because java.lang.RuntimeException: failure message",
            "    When building rule //:target_name.",
            "Not all rules succeeded."),
        getError(getStderr(result)));
  }

  @Test
  public void runtimeExceptionThrownInStepKeepGoing() throws Exception {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory("failure message", RuntimeException.class);
    ProcessResult result = workspace.runBuckBuild("--keep-going", ":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            " ** Summary of failures encountered during the build **",
            "Rule //:target_name FAILED because java.lang.RuntimeException: failure message",
            "    When running <failing_step>.",
            "    When building rule //:target_name.",
            "Not all rules succeeded."),
        getError(getStderr(result)));
  }

  @Test
  public void ioExceptionThrownInStepKeepGoing() throws Exception {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory("failure message", IOException.class);
    ProcessResult result = workspace.runBuckBuild("--keep-going", ":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            " ** Summary of failures encountered during the build **",
            "Rule //:target_name FAILED because java.io.IOException: failure message",
            "    When running <failing_step>.",
            "    When building rule //:target_name.",
            "Not all rules succeeded."),
        getError(getStderr(result)));
  }

  @Test
  public void ioExceptionThrownKeepGoing() throws Exception {
    mockDescription.buildRuleFactory = exceptionTargetFactory("failure message", IOException.class);
    ProcessResult result = workspace.runBuckBuild("--keep-going", ":target_name");
    result.assertFailure();
    assertEquals(
        linesToText(
            " ** Summary of failures encountered during the build **",
            "Rule //:target_name FAILED because java.io.IOException: failure message",
            "    When building rule //:target_name.",
            "Not all rules succeeded."),
        getError(getStderr(result)));
  }

  private String getError(String stderr) {
    String[] lines = stderr.split(System.lineSeparator());
    int start = 0;
    while (start < lines.length) {
      if (lines[start].startsWith("Build failed")
          || lines[start].startsWith("Buck encountered an internal")
          || lines[start].startsWith(" ** Summary of failures encountered during the build **")) {
        break;
      }
      start++;
    }
    List<String> filtered = new ArrayList<>();
    boolean previousWasFrame = false;
    while (start < lines.length) {
      String line = lines[start];
      if (line.startsWith("DOWNLOADED")) {
        break;
      }
      boolean isFrame = line.matches("^\\s*(at .*|\\.\\.\\..*more)");
      if (isFrame) {
        if (!previousWasFrame) {
          filtered.add("<stacktrace>");
        }
        previousWasFrame = true;
      } else {
        previousWasFrame = false;
        filtered.add(line);
      }
      start++;
    }
    return Joiner.on(System.lineSeparator()).join(filtered);
  }

  @SafeVarargs
  private final BuildRuleFactory exceptionTargetFactory(
      String message, Class<? extends Throwable>... exceptionClasses) {
    return exceptionTargetFactory(message, false, exceptionClasses);
  }

  @SafeVarargs
  private final BuildRuleFactory stepExceptionTargetFactory(
      String message, Class<? extends Throwable>... exceptionClasses) {
    return exceptionTargetFactory(message, true, exceptionClasses);
  }

  @SafeVarargs
  private final BuildRuleFactory exceptionTargetFactory(
      String message, boolean inStep, Class<? extends Throwable>... exceptionClasses) {
    return ((buildTarget, projectFilesystem) ->
        new ExceptionRule(
            buildTarget,
            projectFilesystem,
            ImmutableList.copyOf(exceptionClasses),
            message,
            inStep));
  }

  private BuildRuleFactory exitCodeTargetFactory(String message, int exitCode) {
    return ((buildTarget, projectFilesystem) ->
        new ExitCodeRule(buildTarget, projectFilesystem, exitCode, message));
  }

  private BuildRuleFactory successTargetFactory() {
    return ((buildTarget, projectFilesystem) ->
        new NoopBuildRule(buildTarget, projectFilesystem) {
          @Override
          public SortedSet<BuildRule> getBuildDeps() {
            return ImmutableSortedSet.of();
          }
        });
  }

  private static class ExceptionRule extends AbstractBuildRule {
    private final String message;
    private final boolean inStep;
    private final ImmutableList<Class<? extends Throwable>> exceptionClasses;

    public ExceptionRule(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        ImmutableList<Class<? extends Throwable>> exceptionClasses,
        String message,
        boolean inStep) {
      super(buildTarget, projectFilesystem);
      this.exceptionClasses = exceptionClasses;
      this.message = message;
      this.inStep = inStep;
    }

    @Override
    public SortedSet<BuildRule> getBuildDeps() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableList<? extends Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      Throwable throwable = getException(exceptionClasses, message);
      if (!inStep) {
        throw new BuckUncheckedExecutionException(throwable);
      }
      return ImmutableList.of(
          new AbstractExecutionStep("failing_step") {
            @Override
            public StepExecutionResult execute(ExecutionContext context)
                throws IOException, InterruptedException {
              Throwables.throwIfInstanceOf(throwable, IOException.class);
              Throwables.throwIfInstanceOf(throwable, InterruptedException.class);
              Throwables.throwIfUnchecked(throwable);
              throw new BuckUncheckedExecutionException(throwable);
            }
          });
    }

    @Nullable
    @Override
    public SourcePath getSourcePathToOutput() {
      return null;
    }

    private static Throwable getException(
        ImmutableList<Class<? extends Throwable>> exceptionClasses, String message) {
      Throwable cause = null;
      for (Class<? extends Throwable> clz : Lists.reverse(exceptionClasses)) {
        try {
          Object obj;
          if (cause == null) {
            Constructor<?> constructor = clz.getConstructor(String.class);
            obj = constructor.newInstance(message);
          } else {
            Constructor<?> constructor = clz.getConstructor(String.class, Throwable.class);
            obj = constructor.newInstance(message, cause);
          }
          cause = Throwable.class.cast(obj);
          // Modify the message a little so that we can differentiate the message at different
          // levels of nesting.
          message = " <- " + message + " -> ";
        } catch (NoSuchMethodException
            | IllegalAccessException
            | InstantiationException
            | InvocationTargetException e) {
          // Throw an error to hopefully skip all the exception processing and don't accidentally
          // pass the test.
          throw new AssertionError(e);
        }
      }
      return cause;
    }
  }

  private class ExitCodeRule extends AbstractBuildRule {
    private final int exitCode;
    private final String message;

    public ExitCodeRule(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        int exitCode,
        String message) {
      super(buildTarget, projectFilesystem);

      this.exitCode = exitCode;
      this.message = message;
    }

    @Override
    public SortedSet<BuildRule> getBuildDeps() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableList<? extends Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return ImmutableList.of(
          new AbstractExecutionStep("step_with_exit_code_" + exitCode) {
            @Override
            public StepExecutionResult execute(ExecutionContext context) {
              return StepExecutionResult.of(exitCode, Optional.of(message));
            }
          });
    }

    @Nullable
    @Override
    public SourcePath getSourcePathToOutput() {
      return null;
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractMockArg {}

  private class MockDescription implements DescriptionWithTargetGraph<MockArg> {
    private BuildRuleFactory buildRuleFactory = null;

    @Override
    public Class<MockArg> getConstructorArgType() {
      return MockArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        MockArg args) {
      return Preconditions.checkNotNull(buildRuleFactory)
          .create(buildTarget, context.getProjectFilesystem());
    }
  }

  private interface BuildRuleFactory {
    BuildRule create(BuildTarget buildTarget, ProjectFilesystem projectFilesystem);
  }
}

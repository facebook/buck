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

package com.facebook.buck.cli;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
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
    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownNativeRuleTypes.of(
                    ImmutableList.of(mockDescription),
                    knownConfigurationDescriptions,
                    ImmutableList.of()));
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
  public void exceptionWithCauseThrown() {
    mockDescription.buildRuleFactory =
        exceptionTargetFactory("failure message", RuntimeException.class, RuntimeException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertExitCode(null, ExitCode.FATAL_GENERIC);
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "Buck encountered an internal error",
            "java.lang.RuntimeException:  <- failure message -> ",
            "Caused by: java.lang.RuntimeException: failure message",
            "When building rule //:target_name."));
  }

  @Test
  public void exceptionWithCauseThrownInStep() {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory(
            "failure message", RuntimeException.class, RuntimeException.class);
    workspace.runBuckBuild(":target_name");
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertExitCode(null, ExitCode.FATAL_GENERIC);
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "Buck encountered an internal error",
            "java.lang.RuntimeException:  <- failure message -> ",
            "Caused by: java.lang.RuntimeException: failure message",
            "When running <failing_step>.",
            "When building rule //:target_name."));
  }

  @Test
  public void runtimeExceptionThrown() {
    mockDescription.buildRuleFactory =
        exceptionTargetFactory("failure message", RuntimeException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertExitCode(null, ExitCode.FATAL_GENERIC);
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "Buck encountered an internal error",
            "java.lang.RuntimeException: failure message",
            "When building rule //:target_name."));
  }

  @Test
  public void runtimeExceptionThrownInStep() {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory("failure message", RuntimeException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertExitCode(null, ExitCode.FATAL_GENERIC);
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "Buck encountered an internal error",
            "java.lang.RuntimeException: failure message",
            "When running <failing_step>.",
            "When building rule //:target_name."));
  }

  @Test
  public void ioExceptionThrownInStep() {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory("failure message", IOException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertExitCode(null, ExitCode.FATAL_IO);
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "Buck encountered an internal error",
            "java.io.IOException: failure message",
            "When running <failing_step>.",
            "When building rule //:target_name."));
  }

  @Test
  public void ioExceptionThrown() {
    mockDescription.buildRuleFactory = exceptionTargetFactory("failure message", IOException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertExitCode(null, ExitCode.FATAL_IO);
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "Buck encountered an internal error",
            "java.io.IOException: failure message",
            "When building rule //:target_name."));
  }

  @Test
  public void ioExceptionWithRuleInMessageThrown() {
    mockDescription.buildRuleFactory =
        exceptionTargetFactory("failure message //:target_name", IOException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertExitCode(null, ExitCode.FATAL_IO);
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "Buck encountered an internal error",
            "java.io.IOException: failure message //:target_name",
            "When building rule //:target_name."));
  }

  @Test
  public void humanReadableExceptionThrownInStep() {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory("failure message", HumanReadableException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "failure message",
            "When running <failing_step>.",
            "When building rule //:target_name."));
  }

  @Test
  public void humanReadableExceptionThrown() {
    mockDescription.buildRuleFactory =
        exceptionTargetFactory("failure message", HumanReadableException.class);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder("failure message", "When building rule //:target_name."));
  }

  @Test
  public void withStepReturningFailure() {
    mockDescription.buildRuleFactory = exitCodeTargetFactory("failure message", 1);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "Command failed with exit code 1.",
            "stderr: failure message",
            "When running <step_with_exit_code_1>.",
            "When building rule //:target_name."));
  }

  @Test
  public void withSuccessfulStep() {
    mockDescription.buildRuleFactory = exitCodeTargetFactory("success message", 0);
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertSuccess();
    assertEquals("", getError(getStderr(result)));
  }

  @Test
  public void successWithNoSteps() {
    mockDescription.buildRuleFactory = successTargetFactory();
    ProcessResult result = workspace.runBuckBuild(":target_name");
    result.assertSuccess();
    assertEquals("", getError(getStderr(result)));
  }

  @Test
  public void runtimeExceptionThrownKeepGoing() {
    mockDescription.buildRuleFactory =
        exceptionTargetFactory("failure message", RuntimeException.class);
    ProcessResult result = workspace.runBuckBuild("--keep-going", ":target_name");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            " ** Summary of failures encountered during the build **",
            "Rule //:target_name FAILED because",
            "java.lang.RuntimeException: failure message",
            "When building rule //:target_name.",
            "Not all rules succeeded."));
  }

  @Test
  public void runtimeExceptionThrownInStepKeepGoing() {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory("failure message", RuntimeException.class);
    ProcessResult result = workspace.runBuckBuild("--keep-going", ":target_name");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            " ** Summary of failures encountered during the build **",
            "Rule //:target_name FAILED because",
            "java.lang.RuntimeException: failure message",
            "When running <failing_step>.",
            "When building rule //:target_name.",
            "Not all rules succeeded."));
  }

  @Test
  public void ioExceptionThrownInStepKeepGoing() {
    mockDescription.buildRuleFactory =
        stepExceptionTargetFactory("failure message", IOException.class);
    ProcessResult result = workspace.runBuckBuild("--keep-going", ":target_name");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            " ** Summary of failures encountered during the build **",
            "Rule //:target_name FAILED because",
            "java.io.IOException: failure message",
            "When running <failing_step>.",
            "When building rule //:target_name.",
            "Not all rules succeeded."));
  }

  @Test
  public void ioExceptionThrownKeepGoing() {
    mockDescription.buildRuleFactory = exceptionTargetFactory("failure message", IOException.class);
    ProcessResult result = workspace.runBuckBuild("--keep-going", ":target_name");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            " ** Summary of failures encountered during the build **",
            "Rule //:target_name FAILED because",
            "java.io.IOException: failure message",
            "When building rule //:target_name.",
            "Not all rules succeeded."));
  }

  @Test
  public void suggestionsWhenBuildTargetDoesntExist() {
    ProcessResult result =
        workspace
            .runBuckBuild("--keep-going", "//missing_target:bar")
            .assertExitCode(ExitCode.PARSE_ERROR);
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "BUILD FAILED: The rule //missing_target:bar could not be found.",
            "Please check the spelling and whether it is one of the 23 targets in ",
            Paths.get("missing_target", "BUCK").toString(),
            "3 similar targets in ",
            "  //missing_target:barWithSomeLongSuffix",
            "  //missing_target:baz",
            "  //missing_target:foo"));

    assertThat(
        result.getStderr(), Matchers.not(Matchers.containsString("some_long_prefix_string_00")));

    result =
        workspace
            .runBuckBuild("--keep-going", "//missing_target:bazWithSomeLongSuffix")
            .assertExitCode(ExitCode.PARSE_ERROR);
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "BUILD FAILED: The rule //missing_target:bazWithSomeLongSuffix could not be found.",
            "Please check the spelling and whether it is one of the 23 targets in ",
            "2 similar targets in ",
            "  //missing_target:barWithSomeLongSuffix",
            "  //missing_target:baz"));

    assertThat(result.getStderr(), Matchers.not(Matchers.containsString("  //missing_target:foo")));

    result =
        workspace
            .runBuckBuild("--keep-going", "//missing_target:some_long_prefix_string")
            .assertExitCode(ExitCode.PARSE_ERROR);
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "BUILD FAILED: The rule //missing_target:some_long_prefix_string could not be found.",
            "Please check the spelling and whether it is one of the 23 targets in ",
            "15 similar targets in ",
            "some_long_prefix_string_00",
            "some_long_prefix_string_01",
            "some_long_prefix_string_02",
            "some_long_prefix_string_03",
            "some_long_prefix_string_04",
            "some_long_prefix_string_05",
            "some_long_prefix_string_06",
            "some_long_prefix_string_07",
            "some_long_prefix_string_08",
            "some_long_prefix_string_09",
            "some_long_prefix_string_10",
            "some_long_prefix_string_11",
            "some_long_prefix_string_12",
            "some_long_prefix_string_13",
            "some_long_prefix_string_14"));

    assertThat(
        result.getStderr(), Matchers.not(Matchers.containsString("some_long_prefix_string_15")));

    result =
        workspace
            .runBuckBuild("--keep-going", "//missing_target:really_long_string_that_doesnt_match")
            .assertExitCode(ExitCode.PARSE_ERROR);
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "BUILD FAILED: The rule //missing_target:really_long_string_that_doesnt_match could not be found.",
            "Please check the spelling and whether it is one of the 23 targets in "));
    assertThat(result.getStderr(), Matchers.not(Matchers.containsString("Similar targets in")));
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
    return NoopBuildRule::new;
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
          cause = (Throwable) obj;
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
              return StepExecutionResult.builder()
                  .setExitCode(exitCode)
                  .setStderr(Optional.of(message))
                  .build();
            }
          });
    }

    @Nullable
    @Override
    public SourcePath getSourcePathToOutput() {
      return null;
    }
  }

  @RuleArg
  interface AbstractMockArg extends BuildRuleArg {}

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
      return Objects.requireNonNull(buildRuleFactory)
          .create(buildTarget, context.getProjectFilesystem());
    }
  }

  private interface BuildRuleFactory {
    BuildRule create(BuildTarget buildTarget, ProjectFilesystem projectFilesystem);
  }
}

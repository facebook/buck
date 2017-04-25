/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class JavacStepTest {

  @Test
  public void successfulCompileDoesNotSendStdoutAndStderrToConsole() throws Exception {
    FakeJavac fakeJavac = new FakeJavac();
    BuildRuleResolver buildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    ProjectFilesystem fakeFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    JavacOptions javacOptions =
        JavacOptions.builder().setSourceLevel("8.0").setTargetLevel("8.0").build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());

    JavacStep step =
        new JavacStep(
            Paths.get("output"),
            NoOpClassUsageFileWriter.instance(),
            Optional.empty(),
            ImmutableSortedSet.of(),
            Paths.get("pathToSrcsList"),
            ImmutableSortedSet.of(),
            fakeJavac,
            javacOptions,
            BuildTargetFactory.newInstance("//foo:bar"),
            sourcePathResolver,
            fakeFilesystem,
            classpathChecker,
            Optional.empty());

    FakeProcess fakeJavacProcess = new FakeProcess(0, "javac stdout\n", "javac stderr\n");

    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .build();
    BuckEventBusFactory.CapturingConsoleEventListener listener =
        new BuckEventBusFactory.CapturingConsoleEventListener();
    executionContext.getBuckEventBus().register(listener);
    StepExecutionResult result = step.execute(executionContext);

    // Note that we don't include stderr in the step result on success.
    assertThat(result, equalTo(StepExecutionResult.SUCCESS));
    assertThat(listener.getLogMessages(), empty());
  }

  @Test
  public void failedCompileSendsStdoutAndStderrToConsole() throws Exception {
    FakeJavac fakeJavac = new FakeJavac();
    BuildRuleResolver buildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    ProjectFilesystem fakeFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    JavacOptions javacOptions =
        JavacOptions.builder().setSourceLevel("8.0").setTargetLevel("8.0").build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());

    JavacStep step =
        new JavacStep(
            Paths.get("output"),
            NoOpClassUsageFileWriter.instance(),
            Optional.empty(),
            ImmutableSortedSet.of(),
            Paths.get("pathToSrcsList"),
            ImmutableSortedSet.of(),
            fakeJavac,
            javacOptions,
            BuildTargetFactory.newInstance("//foo:bar"),
            sourcePathResolver,
            fakeFilesystem,
            classpathChecker,
            Optional.empty());

    FakeProcess fakeJavacProcess = new FakeProcess(1, "javac stdout\n", "javac stderr\n");

    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .build();
    BuckEventBusFactory.CapturingConsoleEventListener listener =
        new BuckEventBusFactory.CapturingConsoleEventListener();
    executionContext.getBuckEventBus().register(listener);
    StepExecutionResult result = step.execute(executionContext);

    // JavacStep itself writes stdout to the console on error; we expect the Build class to write
    // the stderr stream returned in the StepExecutionResult
    assertThat(result, equalTo(StepExecutionResult.of(1, Optional.of("javac stderr\n"))));
    assertThat(listener.getLogMessages(), equalTo(ImmutableList.of("javac stdout\n")));
  }

  @Test
  public void existingBootclasspathDirSucceeds() throws Exception {
    FakeJavac fakeJavac = new FakeJavac();
    BuildRuleResolver buildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    ProjectFilesystem fakeFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setSourceLevel("8.0")
            .setTargetLevel("8.0")
            .setBootclasspath("/this-totally-exists")
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> true, file -> false, (path, glob) -> ImmutableSet.of());

    JavacStep step =
        new JavacStep(
            Paths.get("output"),
            NoOpClassUsageFileWriter.instance(),
            Optional.empty(),
            ImmutableSortedSet.of(),
            Paths.get("pathToSrcsList"),
            ImmutableSortedSet.of(),
            fakeJavac,
            javacOptions,
            BuildTargetFactory.newInstance("//foo:bar"),
            sourcePathResolver,
            fakeFilesystem,
            classpathChecker,
            Optional.empty());

    FakeProcess fakeJavacProcess = new FakeProcess(0, "javac stdout\n", "javac stderr\n");

    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .build();
    BuckEventBusFactory.CapturingConsoleEventListener listener =
        new BuckEventBusFactory.CapturingConsoleEventListener();
    executionContext.getBuckEventBus().register(listener);
    StepExecutionResult result = step.execute(executionContext);

    assertThat(result, equalTo(StepExecutionResult.SUCCESS));
    assertThat(listener.getLogMessages(), empty());
  }

  @Test
  public void missingBootclasspathDirFailsWithError() throws Exception {
    FakeJavac fakeJavac = new FakeJavac();
    BuildRuleResolver buildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    ProjectFilesystem fakeFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setSourceLevel("8.0")
            .setTargetLevel("8.0")
            .setBootclasspath("/no-such-dir")
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());

    JavacStep step =
        new JavacStep(
            Paths.get("output"),
            NoOpClassUsageFileWriter.instance(),
            Optional.empty(),
            ImmutableSortedSet.of(),
            Paths.get("pathToSrcsList"),
            ImmutableSortedSet.of(),
            fakeJavac,
            javacOptions,
            BuildTargetFactory.newInstance("//foo:bar"),
            sourcePathResolver,
            fakeFilesystem,
            classpathChecker,
            Optional.empty());

    FakeProcess fakeJavacProcess = new FakeProcess(1, "javac stdout\n", "javac stderr\n");

    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .build();
    BuckEventBusFactory.CapturingConsoleEventListener listener =
        new BuckEventBusFactory.CapturingConsoleEventListener();
    executionContext.getBuckEventBus().register(listener);
    StepExecutionResult result = step.execute(executionContext);

    assertThat(result, equalTo(StepExecutionResult.ERROR));
    assertThat(
        listener.getLogMessages(),
        equalTo(
            ImmutableList.of(
                "Invalid Java compiler options: Bootstrap classpath /no-such-dir "
                    + "contains no valid entries")));
  }
}

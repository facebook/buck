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

package com.facebook.buck.jvm.java;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ExternalEvent;
import com.facebook.buck.event.external.events.CompilerErrorEventExternalInterface;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.google.common.base.Functions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import java.io.File;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JavacStepTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private static class ExternalEventListener {

    private final AtomicInteger counter = new AtomicInteger(-1);
    private final Map<Integer, BuckEvent> events = new HashMap<>();

    @Subscribe
    private void externalEvent(ExternalEvent event) {
      events.put(counter.incrementAndGet(), event);
    }
  }

  private ProjectFilesystem fakeFilesystem;
  private BuckPaths buckPaths;
  private BuildTarget target;
  private BuildTargetValue buildTargetValue;
  private FakeJavac fakeJavac;
  private SourcePathResolverAdapter sourcePathResolver;

  @Before
  public void setUp() throws Exception {
    fakeFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    buckPaths = fakeFilesystem.getBuckPaths();
    target = BuildTargetFactory.newInstance("//foo:bar");
    buildTargetValue = BuildTargetValue.of(target, buckPaths);
    fakeJavac = new FakeJavac();
    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();
    sourcePathResolver = buildRuleResolver.getSourcePathResolver();
  }

  @Test
  public void successfulCompileDoesNotSendStdoutAndStderrToConsole() throws Exception {
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder()
                    .setSourceLevel("8.0")
                    .setTargetLevel("8.0")
                    .build())
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());

    JavacStep step =
        new JavacStep(
            fakeJavac,
            ResolvedJavacOptions.of(javacOptions, sourcePathResolver, fakeFilesystem.getRootPath()),
            buildTargetValue,
            buckPaths.getConfiguredBuckOut(),
            CompilerOutputPathsValue.of(buckPaths, target),
            classpathChecker,
            CompilerParameters.builder()
                .setOutputPaths(CompilerOutputPaths.of(target, buckPaths))
                .build(),
            null,
            null,
            false,
            ImmutableMap.of());

    FakeProcess fakeJavacProcess = new FakeProcess(0, "javac stdout\n", "javac stderr\n");

    AbsPath rootPath = fakeFilesystem.getRootPath();
    StepExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .setRuleCellRoot(rootPath)
            .setBuildCellRootPath(rootPath.getPath())
            .build();
    BuckEventBusForTests.CapturingEventListener listener =
        new BuckEventBusForTests.CapturingEventListener();
    executionContext.getBuckEventBus().register(listener);
    StepExecutionResult result = step.execute(executionContext);

    // Note that we don't include stderr in the step result on success.
    assertThat(result, equalTo(StepExecutionResults.SUCCESS));
    assertThat(listener.getConsoleEventLogMessages(), empty());
  }

  @Test
  public void failedCompileSendsStdoutAndStderrToConsole() throws Exception {
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder()
                    .setSourceLevel("8.0")
                    .setTargetLevel("8.0")
                    .build())
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());

    JavacStep step =
        new JavacStep(
            fakeJavac,
            ResolvedJavacOptions.of(javacOptions, sourcePathResolver, fakeFilesystem.getRootPath()),
            buildTargetValue,
            buckPaths.getConfiguredBuckOut(),
            CompilerOutputPathsValue.of(buckPaths, target),
            classpathChecker,
            CompilerParameters.builder()
                .setOutputPaths(CompilerOutputPaths.of(target, buckPaths))
                .build(),
            null,
            null,
            false,
            ImmutableMap.of());

    FakeProcess fakeJavacProcess = new FakeProcess(1, "javac stdout\n", "javac stderr\n");

    AbsPath rootPath = fakeFilesystem.getRootPath();
    StepExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .setRuleCellRoot(rootPath)
            .setBuildCellRootPath(rootPath.getPath())
            .build();
    BuckEventBusForTests.CapturingEventListener listener =
        new BuckEventBusForTests.CapturingEventListener();
    ExternalEventListener externalEventListener = new ExternalEventListener();
    BuckEventBus buckEventBus = executionContext.getBuckEventBus();
    buckEventBus.register(listener);
    buckEventBus.register(externalEventListener);
    StepExecutionResult result = step.execute(executionContext);

    // JavacStep itself writes stdout to the console on error; we expect the Build class to write
    // the stderr stream returned in the StepExecutionResult
    assertThat(
        result,
        equalTo(
            StepExecutionResult.builder()
                .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
                .setStderr(Optional.of("javac stderr\n"))
                .build()));
    assertThat(listener.getConsoleEventLogMessages(), equalTo(ImmutableList.of("javac stdout\n")));

    Map<Integer, BuckEvent> events = externalEventListener.events;
    assertThat(events.keySet(), hasSize(1));

    BuckEvent buckEvent = events.get(0);
    assertTrue(buckEvent instanceof ExternalEvent);
    ExternalEvent externalEvent = (ExternalEvent) buckEvent;

    ImmutableMap<String, String> data = externalEvent.getData();
    assertThat(
        data.get(ExternalEvent.EVENT_TYPE_KEY),
        equalTo(CompilerErrorEventExternalInterface.COMPILER_ERROR_EVENT));

    assertThat(
        data.get(CompilerErrorEventExternalInterface.COMPILER_NAME_KEY).toLowerCase(),
        containsString("java"));
  }

  @Test
  public void existingBootclasspathDirSucceeds() throws Exception {
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder()
                    .setSourceLevel("8.0")
                    .setTargetLevel("8.0")
                    .build())
            .setBootclasspath("/this-totally-exists")
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> true, file -> false, (path, glob) -> ImmutableSet.of());

    JavacStep step =
        new JavacStep(
            fakeJavac,
            ResolvedJavacOptions.of(javacOptions, sourcePathResolver, fakeFilesystem.getRootPath()),
            buildTargetValue,
            buckPaths.getConfiguredBuckOut(),
            CompilerOutputPathsValue.of(buckPaths, target),
            classpathChecker,
            CompilerParameters.builder()
                .setOutputPaths(CompilerOutputPaths.of(target, buckPaths))
                .build(),
            null,
            null,
            false,
            ImmutableMap.of());

    FakeProcess fakeJavacProcess = new FakeProcess(0, "javac stdout\n", "javac stderr\n");

    AbsPath rootPath = fakeFilesystem.getRootPath();
    StepExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .setRuleCellRoot(rootPath)
            .setBuildCellRootPath(rootPath.getPath())
            .build();
    BuckEventBusForTests.CapturingEventListener listener =
        new BuckEventBusForTests.CapturingEventListener();
    executionContext.getBuckEventBus().register(listener);
    StepExecutionResult result = step.execute(executionContext);

    assertThat(result, equalTo(StepExecutionResults.SUCCESS));
    assertThat(listener.getConsoleEventLogMessages(), empty());
  }

  @Test
  public void bootclasspathResolvedToAbsolutePath() {
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder()
                    .setSourceLevel("8.0")
                    .setTargetLevel("8.0")
                    .build())
            .setBootclasspath("/this-totally-exists:relative-path")
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> true, file -> false, (path, glob) -> ImmutableSet.of());

    JavacStep step =
        new JavacStep(
            fakeJavac,
            ResolvedJavacOptions.of(javacOptions, sourcePathResolver, fakeFilesystem.getRootPath()),
            buildTargetValue,
            buckPaths.getConfiguredBuckOut(),
            CompilerOutputPathsValue.of(buckPaths, target),
            classpathChecker,
            CompilerParameters.builder()
                .setOutputPaths(CompilerOutputPaths.of(target, buckPaths))
                .build(),
            null,
            null,
            false,
            ImmutableMap.of());

    FakeProcess fakeJavacProcess = new FakeProcess(0, "javac stdout\n", "javac stderr\n");

    AbsPath rootPath = fakeFilesystem.getRootPath();
    StepExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .setRuleCellRoot(rootPath)
            .setBuildCellRootPath(rootPath.getPath())
            .build();

    String description = step.getDescription(executionContext);
    List<String> options =
        Splitter.on(",")
            .trimResults()
            .splitToList(Splitter.on("Delimiter").splitToList(description).get(0));
    assertThat(options, hasItem("-bootclasspath"));
    int bootclasspathIndex = options.indexOf("-bootclasspath");
    String bootclasspath = options.get(bootclasspathIndex + 1);
    assertThat(bootclasspath, Matchers.not(Matchers.emptyOrNullString()));
    for (String path : Splitter.on(File.pathSeparator).split(bootclasspath)) {
      assertTrue(Paths.get(path).isAbsolute());
    }
  }

  @Test
  public void missingBootclasspathDirFailsWithError() throws Exception {
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder()
                    .setSourceLevel("8.0")
                    .setTargetLevel("8.0")
                    .build())
            .setBootclasspath("/no-such-dir")
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());

    JavacStep step =
        new JavacStep(
            fakeJavac,
            ResolvedJavacOptions.of(javacOptions, sourcePathResolver, fakeFilesystem.getRootPath()),
            buildTargetValue,
            fakeFilesystem.getBuckPaths().getConfiguredBuckOut(),
            CompilerOutputPathsValue.of(fakeFilesystem.getBuckPaths(), target),
            classpathChecker,
            CompilerParameters.builder()
                .setOutputPaths(CompilerOutputPaths.of(target, fakeFilesystem.getBuckPaths()))
                .build(),
            null,
            null,
            false,
            ImmutableMap.of());

    FakeProcess fakeJavacProcess = new FakeProcess(1, "javac stdout\n", "javac stderr\n");

    StepExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .build();
    BuckEventBusForTests.CapturingEventListener listener =
        new BuckEventBusForTests.CapturingEventListener();
    executionContext.getBuckEventBus().register(listener);
    thrown.expectMessage("Bootstrap classpath /no-such-dir contains no valid entries");
    step.execute(executionContext);
  }
}

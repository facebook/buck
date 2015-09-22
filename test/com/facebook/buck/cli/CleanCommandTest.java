/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.newCapture;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.java.intellij.Project;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;

import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Unit test for {@link CleanCommand}.
 */
public class CleanCommandTest extends EasyMockSupport {

  private ProjectFilesystem projectFilesystem;

  // TODO(mbolin): When it is possible to inject a mock object for stderr,
  // create a test that runs `buck clean unexpectedarg` and verify that the
  // exit code is 1 and that the appropriate error message is printed.

  @Test
  public void testCleanCommandNoArguments()
      throws CmdLineException, IOException, InterruptedException {
    // Set up mocks.
    CommandRunnerParams params = createCommandRunnerParams();
    Capture<Path> binDir = newCapture();
    projectFilesystem.deleteRecursivelyIfExists(capture(binDir));
    Capture<Path> genDir = newCapture();
    projectFilesystem.deleteRecursivelyIfExists(capture(genDir));

    replayAll();

    // Simulate `buck clean`.
    CleanCommand cleanCommand = createCommandFromArgs();
    int exitCode = cleanCommand.run(params);
    assertEquals(0, exitCode);
    assertEquals(BuckConstant.SCRATCH_PATH, binDir.getValue());
    assertEquals(BuckConstant.GEN_PATH, genDir.getValue());

    verifyAll();
  }

  @Test
  public void testCleanCommandWithProjectArgument()
      throws CmdLineException, IOException, InterruptedException {
    // Set up mocks.
    CommandRunnerParams params = createCommandRunnerParams();
    Capture<Path> androidGenDir = newCapture();
    projectFilesystem.deleteRecursivelyIfExists(capture(androidGenDir));
    Capture<Path> annotationDir = newCapture();
    projectFilesystem.deleteRecursivelyIfExists(capture(annotationDir));

    replayAll();

    // Simulate `buck clean --project`.
    CleanCommand cleanCommand = createCommandFromArgs("--project");
    int exitCode = cleanCommand.run(params);
    assertEquals(0, exitCode);
    assertEquals(Project.ANDROID_GEN_PATH, androidGenDir.getValue());
    assertEquals(BuckConstant.ANNOTATION_PATH, annotationDir.getValue());

    verifyAll();
  }

  private CleanCommand createCommandFromArgs(String... args) throws CmdLineException {
    CleanCommand command = new CleanCommand();
    new AdditionalOptionsCmdLineParser(command).parseArgument(args);
    return command;
  }

  private CommandRunnerParams createCommandRunnerParams() throws InterruptedException, IOException {
    projectFilesystem = createMock(ProjectFilesystem.class);
    Cell cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();

    Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier =
        AndroidPlatformTarget.EXPLODING_ANDROID_PLATFORM_TARGET_SUPPLIER;
    return new CommandRunnerParams(
        new TestConsole(),
        cell,
        androidPlatformTargetSupplier,
        createMock(ArtifactCache.class),
        BuckEventBusFactory.newInstance(),
        createMock(Parser.class),
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()),
        new FakeJavaPackageFinder(),
        new ObjectMapper(),
        new DefaultClock(),
        Optional.<ProcessManager>absent(),
        Optional.<WebServer>absent(),
        new FakeBuckConfig(),
        new NullFileHashCache());
  }

}

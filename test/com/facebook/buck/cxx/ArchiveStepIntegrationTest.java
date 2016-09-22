/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class ArchiveStepIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  @SuppressWarnings("PMD.AvoidUsingOctalValues")
  public void thatGeneratedArchivesAreDeterministic() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    CxxPlatform platform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(FakeBuckConfig.builder().build()));

    // Build up the paths to various files the archive step will use.
    SourcePathResolver sourcePathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    Tool archiver = platform.getAr();
    Path output = filesystem.getRootPath().getFileSystem().getPath("output.a");
    Path input = filesystem.getRootPath().getFileSystem().getPath("input.dat");
    filesystem.writeContentsToPath("blah", input);
    Preconditions.checkState(filesystem.resolve(input).toFile().setExecutable(true));

    // Build an archive step.
    ArchiveStep archiveStep = new ArchiveStep(
        filesystem,
        archiver.getEnvironment(sourcePathResolver),
        archiver.getCommandPrefix(sourcePathResolver),
        ImmutableList.<String>of(),
        Archive.Contents.NORMAL,
        output,
        ImmutableList.of(input));
    FileScrubberStep fileScrubberStep = new FileScrubberStep(
        filesystem,
        output,
        platform.getAr().getScrubbers());

    // Execute the archive step and verify it ran successfully.
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    TestConsole console = (TestConsole) executionContext.getConsole();
    int exitCode = archiveStep.execute(executionContext).getExitCode();
    assertEquals("archive step failed: " + console.getTextWrittenToStdErr(), 0, exitCode);
    exitCode = fileScrubberStep.execute(executionContext).getExitCode();
    assertEquals("archive scrub step failed: " + console.getTextWrittenToStdErr(), 0, exitCode);

    // Now read the archive entries and verify that the timestamp, UID, and GID fields are
    // zero'd out.
    try (ArArchiveInputStream stream =
             new ArArchiveInputStream(new FileInputStream(filesystem.resolve(output).toFile()))) {
      ArArchiveEntry entry = stream.getNextArEntry();
      assertEquals(
          ObjectFileCommonModificationDate.COMMON_MODIFICATION_TIME_STAMP,
          entry.getLastModified());
      assertEquals(0, entry.getUserId());
      assertEquals(0, entry.getGroupId());
      assertEquals(String.format("0%o", entry.getMode()), 0100644, entry.getMode());
    }
  }

  @Test
  public void emptyArchives() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    CxxPlatform platform =
        DefaultCxxPlatforms.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    // Build up the paths to various files the archive step will use.
    SourcePathResolver sourcePathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    Tool archiver = platform.getAr();
    Path output = filesystem.getRootPath().getFileSystem().getPath("output.a");

    // Build an archive step.
    ArchiveStep archiveStep =
        new ArchiveStep(
            filesystem,
            archiver.getEnvironment(sourcePathResolver),
            archiver.getCommandPrefix(sourcePathResolver),
            ImmutableList.<String>of(),
            Archive.Contents.NORMAL,
            output,
            ImmutableList.<Path>of());

    // Execute the archive step and verify it ran successfully.
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    TestConsole console = (TestConsole) executionContext.getConsole();
    int exitCode = archiveStep.execute(executionContext).getExitCode();
    assertEquals("archive step failed: " + console.getTextWrittenToStdErr(), 0, exitCode);

    // Now read the archive entries and verify that the timestamp, UID, and GID fields are
    // zero'd out.
    try (ArArchiveInputStream stream = new ArArchiveInputStream(
        new FileInputStream(filesystem.resolve(output).toFile()))) {
      assertThat(stream.getNextArEntry(), Matchers.nullValue());
    }
  }

  @Test
  public void inputDirs() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    CxxPlatform platform =
        DefaultCxxPlatforms.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    // Build up the paths to various files the archive step will use.
    SourcePathResolver sourcePathResolver =
        new SourcePathResolver(
            new BuildRuleResolver(
                TargetGraph.EMPTY,
                new DefaultTargetNodeToBuildRuleTransformer()));
    Tool archiver = platform.getAr();
    Path output = filesystem.getRootPath().getFileSystem().getPath("output.a");
    Path input = filesystem.getRootPath().getFileSystem().getPath("foo/blah.dat");
    filesystem.mkdirs(input.getParent());
    filesystem.writeContentsToPath("blah", input);

    // Build an archive step.
    ArchiveStep archiveStep =
        new ArchiveStep(
            filesystem,
            archiver.getEnvironment(sourcePathResolver),
            archiver.getCommandPrefix(sourcePathResolver),
            ImmutableList.<String>of(),
            Archive.Contents.NORMAL,
            output,
            ImmutableList.of(input.getParent()));

    // Execute the archive step and verify it ran successfully.
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    TestConsole console = (TestConsole) executionContext.getConsole();
    int exitCode = archiveStep.execute(executionContext).getExitCode();
    assertEquals("archive step failed: " + console.getTextWrittenToStdErr(), 0, exitCode);

    // Now read the archive entries and verify that the timestamp, UID, and GID fields are
    // zero'd out.
    try (ArArchiveInputStream stream = new ArArchiveInputStream(
         new FileInputStream(filesystem.resolve(output).toFile()))) {
      ArArchiveEntry entry = stream.getNextArEntry();
      assertThat(entry.getName(), Matchers.equalTo("blah.dat"));
    }
  }

  @Test
  public void thinArchives() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    CxxPlatform platform =
        DefaultCxxPlatforms.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    assumeTrue(platform.getAr().supportsThinArchives());

    // Build up the paths to various files the archive step will use.
    SourcePathResolver sourcePathResolver =
        new SourcePathResolver(
            new BuildRuleResolver(
                TargetGraph.EMPTY,
                new DefaultTargetNodeToBuildRuleTransformer()));
    Tool archiver = platform.getAr();

    Path output = filesystem.getRootPath().getFileSystem().getPath("foo/libthin.a");
    filesystem.mkdirs(output.getParent());

    // Create a really large input file so it's obvious that the archive is thin.
    Path input = filesystem.getRootPath().getFileSystem().getPath("bar/blah.dat");
    filesystem.mkdirs(input.getParent());
    byte[] largeInputFile = new byte[1024 * 1024];
    byte[] fillerToRepeat = "hello\n".getBytes(StandardCharsets.US_ASCII);
    for (int i = 0; i < largeInputFile.length; i++) {
      largeInputFile[i] = fillerToRepeat[i % fillerToRepeat.length];
    }
    filesystem.writeBytesToPath(largeInputFile, input);

    // Build an archive step.
    ArchiveStep archiveStep =
        new ArchiveStep(
            filesystem,
            archiver.getEnvironment(sourcePathResolver),
            archiver.getCommandPrefix(sourcePathResolver),
            ImmutableList.<String>of(),
            Archive.Contents.THIN,
            output,
            ImmutableList.of(input));

    // Execute the archive step and verify it ran successfully.
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    TestConsole console = (TestConsole) executionContext.getConsole();
    int exitCode = archiveStep.execute(executionContext).getExitCode();
    assertEquals("archive step failed: " + console.getTextWrittenToStdErr(), 0, exitCode);

    // Verify that the thin header is present.
    assertThat(filesystem.readFirstLine(output), Matchers.equalTo(Optional.of("!<thin>")));

    // Verify that even though the archived contents is really big, the archive is still small.
    assertThat(filesystem.getFileSize(output), Matchers.lessThan(1000L));

    // NOTE: Replace the thin header with a normal header just so the commons compress parser
    // can parse the archive contents.
    try (OutputStream outputStream =
             Files.newOutputStream(filesystem.resolve(output), StandardOpenOption.WRITE)) {
      outputStream.write(ObjectFileScrubbers.GLOBAL_HEADER);
    }

    // Now read the archive entries and verify that the timestamp, UID, and GID fields are
    // zero'd out.
    try (ArArchiveInputStream stream = new ArArchiveInputStream(
        new FileInputStream(filesystem.resolve(output).toFile()))) {
      ArArchiveEntry entry = stream.getNextArEntry();

      // Verify that the input names are relative paths from the outputs parent dir.
      assertThat(
          entry.getName(),
          Matchers.equalTo(output.getParent().relativize(input).toString()));
    }
  }

}

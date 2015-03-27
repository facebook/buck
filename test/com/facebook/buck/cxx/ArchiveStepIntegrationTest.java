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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.junit.Rule;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ArchiveStepIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  @SuppressWarnings("PMD.AvoidUsingOctalValues")
  public void thatGeneratedArchivesAreDeterministic() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot().toPath());
    CxxPlatform platform = DefaultCxxPlatforms.build(new CxxBuckConfig(new FakeBuckConfig()));

    // Build up the paths to various files the archive step will use.
    ImmutableList<String> archiver =
        platform.getAr().getCommandPrefix(new SourcePathResolver(new BuildRuleResolver()));
    Path output = filesystem.resolve(Paths.get("output.a"));
    Path relativeInput = Paths.get("input.dat");
    Path input = filesystem.resolve(relativeInput);
    filesystem.writeContentsToPath("blah", relativeInput);
    Preconditions.checkState(input.toFile().setExecutable(true));

    // Build an archive step.
    ArchiveStep archiveStep = new ArchiveStep(
        archiver,
        output,
        ImmutableList.of(input));
    ArchiveScrubberStep archiveScrubberStep = new ArchiveScrubberStep(output);

    // Execute the archive step and verify it ran successfully.
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProjectFilesystem(new ProjectFilesystem(tmp.getRoot().toPath()))
            .build();
    TestConsole console = (TestConsole) executionContext.getConsole();
    int exitCode = archiveStep.execute(executionContext);
    assertEquals("archive step failed: " + console.getTextWrittenToStdErr(), 0, exitCode);
    exitCode = archiveScrubberStep.execute(executionContext);
    assertEquals("archive scrub step failed: " + console.getTextWrittenToStdErr(), 0, exitCode);

    // Now read the archive entries and verify that the timestamp, UID, and GID fields are
    // zero'd out.
    try (ArArchiveInputStream stream = new ArArchiveInputStream(
        new FileInputStream(output.toFile()))) {
      ArArchiveEntry entry = stream.getNextArEntry();
      assertEquals(0, entry.getLastModified());
      assertEquals(0, entry.getUserId());
      assertEquals(0, entry.getGroupId());
      assertEquals(String.format("0%o", entry.getMode()), 0100644, entry.getMode());
    }
  }

}

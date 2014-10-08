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
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.junit.Rule;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ArchiveStepIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void thatGeneratedArchivesAreDeterministic() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot().toPath());
    CxxPlatform platform = new DefaultCxxPlatform(new FakeBuckConfig());

    // Build up the paths to various files the archive step will use.
    Path archiver = new SourcePathResolver(new BuildRuleResolver()).getPath(platform.getAr());
    Path output = filesystem.resolve(Paths.get("output.a"));
    Path relativeInput = Paths.get("input.dat");
    Path input = filesystem.resolve(relativeInput);
    filesystem.writeContentsToPath("blah", relativeInput);

    // Build an archive step.
    ArchiveStep archiveStep = new ArchiveStep(
        archiver,
        output,
        ImmutableList.of(input));
    ArchiveScrubberStep archiveScrubberStep = new ArchiveScrubberStep(output);

    // Execute the archive step and verify it ran successfully.
    assumeTrue(Files.exists(archiver));
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
    }
  }

}

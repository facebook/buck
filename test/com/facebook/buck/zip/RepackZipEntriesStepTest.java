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

package com.facebook.buck.zip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class RepackZipEntriesStepTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private Path parent;
  private Path zipFile;
  private ProjectFilesystem filesystem;

  @Before
  public void buildSampleZipFile() throws IOException {
    parent = tmp.newFolder("foo");
    filesystem = TestProjectFilesystems.createProjectFilesystem(parent);
    zipFile = parent.resolve("example.zip");

    // Turns out that the zip filesystem generates slightly different output from the output stream.
    // Since we've modeled our outputstreams after the zip output stream, be compatible with that.
    try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(zipFile))) {
      ZipEntry entry = new ZipEntry("file");
      stream.putNextEntry(entry);
      String packageName = getClass().getPackage().getName().replace('.', '/');
      URL sample = Resources.getResource(packageName + "/sample-bytes.dat");
      stream.write(Resources.toByteArray(sample));
    }
  }

  @Test
  public void shouldLeaveZipAloneIfEntriesToCompressIsEmpty() throws Exception {
    Path out = parent.resolve("output.zip");
    RepackZipEntriesStep step =
        new RepackZipEntriesStep(filesystem, zipFile, out, ImmutableSet.of());
    step.execute(TestExecutionContext.newInstance());

    byte[] expected = Files.readAllBytes(zipFile);
    byte[] actual = Files.readAllBytes(out);
    assertArrayEquals(expected, actual);
  }

  @Test
  public void repackWithHigherCompressionResultsInFewerBytes() throws Exception {
    Path out = parent.resolve("output.zip");
    RepackZipEntriesStep step =
        new RepackZipEntriesStep(filesystem, zipFile, out, ImmutableSet.of("file"));
    step.execute(TestExecutionContext.newInstance());

    assertTrue(Files.size(out) < Files.size(zipFile));
  }

  @Test
  public void justStoringEntriesLeadsToMoreBytesInOuputZip() throws Exception {
    Path out = parent.resolve("output.zip");
    RepackZipEntriesStep step =
        new RepackZipEntriesStep(
            filesystem, zipFile, out, ImmutableSet.of("file"), ZipCompressionLevel.NONE);
    step.execute(TestExecutionContext.newInstance());

    byte[] expected = Files.readAllBytes(zipFile);
    byte[] actual = Files.readAllBytes(out);

    assertTrue(expected.length < actual.length);
  }
}

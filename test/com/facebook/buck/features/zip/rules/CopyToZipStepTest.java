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

package com.facebook.buck.features.zip.rules;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.zip.collect.OnDuplicateEntry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CopyToZipStepTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;
  private Path outputPath;

  @Before
  public void setUp() throws Exception {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    outputPath = filesystem.resolve("output.zip").toAbsolutePath();
  }

  @Test
  public void descriptionWithAllInformation() {
    Path sourceFile1 = filesystem.resolve("sourceFile1").toAbsolutePath();
    Path sourceFile2 = filesystem.resolve("sourceFile2").toAbsolutePath();
    ImmutableMap<Path, Path> sources =
        ImmutableMap.of(
            Paths.get("entry1"), sourceFile1,
            Paths.get("entry2"), sourceFile2);
    Path zipFile1 = filesystem.resolve("zipFile1");
    Path zipFile2 = filesystem.resolve("zipFile2");
    ImmutableList<Path> zipSources = ImmutableList.of(zipFile1, zipFile2);
    ImmutableSet<Pattern> entriesToExclude =
        ImmutableSet.of(Pattern.compile("e.*"), Pattern.compile("META-INF"));
    CopyToZipStep step =
        new CopyToZipStep(
            filesystem,
            outputPath,
            sources,
            zipSources,
            entriesToExclude,
            OnDuplicateEntry.OVERWRITE);

    String expected =
        String.format(
            "Create zip archive %s with source files [entry1=%s, entry2=%s]"
                + " and with source zip files [%s, %s] excluding entries matching [e.*, META-INF]",
            outputPath, sourceFile1, sourceFile2, zipFile1, zipFile2);

    assertEquals(expected, step.getDescription(TestExecutionContext.newInstance()));
  }

  @Test
  public void descriptionWithSourcesOnly() {
    Path sourceFile1 = filesystem.resolve("sourceFile1").toAbsolutePath();
    Path sourceFile2 = filesystem.resolve("sourceFile2").toAbsolutePath();
    ImmutableMap<Path, Path> sources =
        ImmutableMap.of(
            Paths.get("entry1"), sourceFile1,
            Paths.get("entry2"), sourceFile2);
    CopyToZipStep step =
        new CopyToZipStep(
            filesystem,
            outputPath,
            sources,
            ImmutableList.of(),
            ImmutableSet.of(),
            OnDuplicateEntry.OVERWRITE);

    String expected =
        String.format(
            "Create zip archive %s with source files [entry1=%s, entry2=%s]",
            outputPath, sourceFile1, sourceFile2);

    assertEquals(expected, step.getDescription(TestExecutionContext.newInstance()));
  }

  @Test
  public void descriptionWithZipSourcesOnly() {
    Path zipFile1 = filesystem.resolve("zipFile1");
    Path zipFile2 = filesystem.resolve("zipFile2");
    ImmutableList<Path> zipSources = ImmutableList.of(zipFile1, zipFile2);
    CopyToZipStep step =
        new CopyToZipStep(
            filesystem,
            outputPath,
            ImmutableMap.of(),
            zipSources,
            ImmutableSet.of(),
            OnDuplicateEntry.OVERWRITE);

    String expected =
        String.format(
            "Create zip archive %s with source zip files [%s, %s]", outputPath, zipFile1, zipFile2);

    assertEquals(expected, step.getDescription(TestExecutionContext.newInstance()));
  }
}

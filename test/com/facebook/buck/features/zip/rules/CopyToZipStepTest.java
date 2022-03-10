/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.zip.collect.OnDuplicateEntry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.regex.Pattern;
import org.junit.Test;

public class CopyToZipStepTest {

  private static final RelPath OUTPUT_PATH = RelPath.get("output.zip");

  @Test
  public void descriptionWithAllInformation() {
    RelPath sourceFile1 = RelPath.get("sourceFile1");
    RelPath sourceFile2 = RelPath.get("sourceFile2");
    ImmutableMap<RelPath, RelPath> sources =
        ImmutableMap.of(
            RelPath.get("entry1"), sourceFile1,
            RelPath.get("entry2"), sourceFile2);
    RelPath zipFile1 = RelPath.get("zipFile1");
    RelPath zipFile2 = RelPath.get("zipFile2");
    ImmutableList<RelPath> zipSources = ImmutableList.of(zipFile1, zipFile2);
    ImmutableSet<Pattern> entriesToExclude =
        ImmutableSet.of(Pattern.compile("e.*"), Pattern.compile("META-INF"));
    CopyToZipStep step =
        new CopyToZipStep(
            OUTPUT_PATH, sources, zipSources, entriesToExclude, OnDuplicateEntry.OVERWRITE);

    String expected =
        String.format(
            "Create zip archive %s with source files [entry1=%s, entry2=%s]"
                + " and with source zip files [%s, %s] excluding entries matching [e.*, META-INF]",
            OUTPUT_PATH, sourceFile1, sourceFile2, zipFile1, zipFile2);

    assertEquals(expected, step.getIsolatedStepDescription(TestExecutionContext.newInstance()));
  }

  @Test
  public void descriptionWithSourcesOnly() {
    RelPath sourceFile1 = RelPath.get("sourceFile1");
    RelPath sourceFile2 = RelPath.get("sourceFile2");
    ImmutableMap<RelPath, RelPath> sources =
        ImmutableMap.of(
            RelPath.get("entry1"), sourceFile1,
            RelPath.get("entry2"), sourceFile2);
    CopyToZipStep step =
        new CopyToZipStep(
            OUTPUT_PATH,
            sources,
            ImmutableList.of(),
            ImmutableSet.of(),
            OnDuplicateEntry.OVERWRITE);

    String expected =
        String.format(
            "Create zip archive %s with source files [entry1=%s, entry2=%s]",
            OUTPUT_PATH, sourceFile1, sourceFile2);

    assertEquals(expected, step.getIsolatedStepDescription(TestExecutionContext.newInstance()));
  }

  @Test
  public void descriptionWithZipSourcesOnly() {
    RelPath zipFile1 = RelPath.get("zipFile1");
    RelPath zipFile2 = RelPath.get("zipFile2");
    ImmutableList<RelPath> zipSources = ImmutableList.of(zipFile1, zipFile2);
    CopyToZipStep step =
        new CopyToZipStep(
            OUTPUT_PATH,
            ImmutableMap.of(),
            zipSources,
            ImmutableSet.of(),
            OnDuplicateEntry.OVERWRITE);

    String expected =
        String.format(
            "Create zip archive %s with source zip files [%s, %s]",
            OUTPUT_PATH, zipFile1, zipFile2);

    assertEquals(expected, step.getIsolatedStepDescription(TestExecutionContext.newInstance()));
  }
}

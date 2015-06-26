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

package com.facebook.buck.java.intellij;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class IjFolderTest {

  @Test
  public void testMergeForSamePath() {
    IjFolder.Builder folderBuilder = IjFolder.builder()
        .setPath(Paths.get("src"))
        .setWantsPackagePrefix(false);

    IjFolder sourceFolder = folderBuilder
        .setType(IjFolder.Type.SOURCE_FOLDER)
        .setInputs(ImmutableSortedSet.of(Paths.get("Source.java")))
        .build();
    IjFolder testFolder = folderBuilder
        .setType(IjFolder.Type.TEST_FOLDER)
        .setInputs(ImmutableSortedSet.of(Paths.get("Test.java")))
        .build();
    IjFolder excludeFolder = folderBuilder
        .setType(IjFolder.Type.EXCLUDE_FOLDER)
        .setInputs(ImmutableSortedSet.<Path>of())
        .build();

    assertEquals("Merging the folder with itself is that folder.",
        sourceFolder,
        sourceFolder.merge(sourceFolder));

    assertEquals("Merging the folder with itself is that folder.",
        testFolder,
        testFolder.merge(testFolder));

    IjFolder mergedSourceAndTest = folderBuilder
        .setType(IjFolder.Type.SOURCE_FOLDER)
        .setInputs(ImmutableSortedSet.of(
                Paths.get("Source.java"),
                Paths.get("Test.java")
            ))
        .build();

    assertEquals("Merging prod with test means test is promoted to prod.",
        mergedSourceAndTest,
        testFolder.merge(sourceFolder));

    assertEquals("Merging prod with test means test is promoted to prod in either order.",
        mergedSourceAndTest,
        sourceFolder.merge(testFolder));

    assertEquals("Merging the folder with itself is that folder.",
        excludeFolder,
        excludeFolder.merge(excludeFolder));
  }
}

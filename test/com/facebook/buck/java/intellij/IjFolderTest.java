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

import org.junit.Test;

import java.nio.file.Paths;

public class IjFolderTest {

  @Test
  public void testMergeForSamePath() {
    IjFolder.Builder folderBuilder = IjFolder.builder()
        .setPath(Paths.get("src"))
        .setWantsPackagePrefix(false);

    IjFolder sourceFolder = folderBuilder
        .setType(IjFolder.Type.SOURCE_FOLDER)
        .build();
    IjFolder testFolder = folderBuilder
        .setType(IjFolder.Type.TEST_FOLDER)
        .build();
    IjFolder excludeFolder = folderBuilder
        .setType(IjFolder.Type.EXCLUDE_FOLDER)
        .build();

    assertEquals("Merging the folder with itself is that folder.",
        sourceFolder,
        sourceFolder.merge(sourceFolder));

    assertEquals("Merging the folder with itself is that folder.",
        testFolder,
        testFolder.merge(testFolder));

    assertEquals("Merging prod with test means test is promoted to prod.",
        sourceFolder,
        testFolder.merge(sourceFolder));

    assertEquals("Merging prod with test means test is promoted to prod in either order.",
        sourceFolder,
        sourceFolder.merge(testFolder));

    assertEquals("Merging the folder with itself is that folder.",
        excludeFolder,
        excludeFolder.merge(excludeFolder));
  }
}

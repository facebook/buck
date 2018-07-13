/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.features.project.intellij.model.folders.ExcludeFolder;
import com.facebook.buck.features.project.intellij.model.folders.SourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.TestFolder;
import org.junit.Before;
import org.junit.Test;

public class SourceFolderTest extends IjFolderTest {

  @Before
  @Override
  public void setupFolderFactory() {
    folderFactory = SourceFolder.FACTORY;
  }

  @Test(expected = IllegalArgumentException.class)
  public void testMergingExcludeWithExcludeFails() {
    testMerge(ExcludeFolder.FACTORY);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testMergingExcludeWithTestFails() {
    testMerge(TestFolder.FACTORY);
  }
}

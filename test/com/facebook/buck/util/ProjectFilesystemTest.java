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

package com.facebook.buck.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

/** Unit test for {@link ProjectFilesystem}. */
public class ProjectFilesystemTest {

  @Test
  public void testIsFile() {
    ProjectFilesystem filesystem = new ProjectFilesystem(new File("."));

    assertTrue(filesystem.isFile("testdata/com/facebook/buck/util/ProjectFilesystemFile.txt"));
    assertFalse(filesystem.isFile("i_do_not_exist"));
    assertFalse("testdata/ is a directory, but not an ordinary file",
        filesystem.isFile("testdata"));
  }
}

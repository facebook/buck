/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.io.filesystem.skylark;

import static org.junit.Assert.*;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.devtools.build.lib.vfs.Path;
import org.junit.Before;
import org.junit.Test;

public class SkylarkFilesystemTest {

  private ProjectFilesystem projectFilesystem;
  private SkylarkFilesystem skylarkFilesystem;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    skylarkFilesystem = SkylarkFilesystem.using(projectFilesystem);
  }

  private Path toSkylarkPath(String path) {
    return skylarkFilesystem.getPath(projectFilesystem.resolve(path).toString());
  }

  private Path toSkylarkPath(java.nio.file.Path path) {
    return toSkylarkPath(path.toString());
  }

  @Test
  public void fileCreatedUsingProjectFileSystemIsVisibleToSkylark() throws Exception {
    java.nio.file.Path file = projectFilesystem.getPathForRelativePath("file");
    projectFilesystem.createNewFile(file);
    assertTrue(toSkylarkPath(file).exists());
  }
}

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

package com.facebook.buck.util.unarchive;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DirectoryCreatorTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void mkdirsCreatesDirectoryIfHasntBeenCreated() throws IOException {
    Path path = Paths.get("foo", "bar");
    DirectoryCreator creator = new DirectoryCreator(filesystem);

    creator.mkdirs(path);

    Assert.assertTrue(filesystem.exists(path));
    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo")));
    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo", "bar")));
  }

  @Test
  public void mkdirsDoesNotCreateDirectoryIfAlreadyCreated() throws IOException {
    Path path = Paths.get("foo", "bar");
    DirectoryCreator creator = new DirectoryCreator(filesystem);

    creator.mkdirs(path);
    filesystem.deleteRecursivelyIfExists(path);
    Assert.assertFalse(filesystem.exists(path));
    creator.mkdirs(path);

    Assert.assertFalse(filesystem.exists(path));
    // Recorded on first creation
    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo")));
    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo", "bar")));
  }

  @Test
  public void mkdirsRecordsParentDirectoriesIfChildDoesNotExistButParentDoes() throws IOException {
    Path path = Paths.get("foo", "bar");
    DirectoryCreator creator = new DirectoryCreator(filesystem);

    filesystem.mkdirs(path.getParent());
    creator.mkdirs(path);

    Assert.assertTrue(filesystem.exists(path));
    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo")));
    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo", "bar")));
  }

  @Test
  public void recordPathRecordsProperly() {
    Path path = Paths.get("foo", "bar");
    DirectoryCreator creator = new DirectoryCreator(filesystem);

    creator.recordPath(path);

    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo", "bar")));
  }

  @Test
  public void forcefullyCreateDirsDoesNothingIfDirectoryAlreadyExists() throws IOException {
    Path path = Paths.get("foo", "bar");
    DirectoryCreator creator = new DirectoryCreator(filesystem);

    filesystem.mkdirs(path);
    filesystem.writeContentsToPath("test", path.resolve("subfile"));
    creator.forcefullyCreateDirs(path);

    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo", "bar")));
    Assert.assertTrue(filesystem.exists(path.resolve("subfile")));
  }

  @Test
  public void forcefullyCreateDirsDeletesFileAndCreatesDirAlreadyExists() throws IOException {
    Path path = Paths.get("foo", "bar");
    DirectoryCreator creator = new DirectoryCreator(filesystem);

    filesystem.mkdirs(path.getParent());
    filesystem.writeContentsToPath("test", path);
    creator.forcefullyCreateDirs(path);

    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo", "bar")));
    Assert.assertTrue(filesystem.isDirectory(path));
  }

  @Test
  public void forcefullyCreateDirsRemovesParentFilesIfParentExistsAndChildDoesNot()
      throws IOException {
    Path path = Paths.get("foo", "bar");
    DirectoryCreator creator = new DirectoryCreator(filesystem);

    filesystem.writeContentsToPath("test", path.getParent());
    creator.forcefullyCreateDirs(path);

    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo")));
    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo", "bar")));
    Assert.assertTrue(filesystem.isDirectory(path.getParent()));
    Assert.assertTrue(filesystem.isDirectory(path));
  }

  @Test
  public void forcefullyCreateDirsDoesNotCreateAnythingIfAlreadyAdded() throws IOException {
    Path path = Paths.get("foo", "bar");
    DirectoryCreator creator = new DirectoryCreator(filesystem);

    creator.forcefullyCreateDirs(path);
    filesystem.deleteRecursivelyIfExists(path);
    Assert.assertFalse(filesystem.exists(path));
    creator.forcefullyCreateDirs(path);

    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo")));
    Assert.assertTrue(creator.recordedDirectories().contains(Paths.get("foo", "bar")));
    Assert.assertFalse(filesystem.exists(path));
  }
}

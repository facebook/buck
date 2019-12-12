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

package com.facebook.buck.util.hashing;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;

public class FilePathHashLoaderTest {

  private final FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
  private Path cellRoot;
  private Path file;
  private Path directory;
  private Path fileInDirectory;

  @Before
  public void setUpFileSystem() throws IOException {
    cellRoot = vfs.getPath("/path/to/the/cell/root");
    file = cellRoot.resolve("a.txt");
    directory = cellRoot.resolve("dir");
    fileInDirectory = directory.resolve("a.txt");
    Files.createDirectories(cellRoot);
    Files.createDirectory(directory);
    Files.write(file, "Hello!".getBytes());
    Files.write(fileInDirectory, "Hello!".getBytes());
  }

  @Test
  public void returnsDifferentHashesForDifferentPaths() throws IOException {
    FilePathHashLoader loader = newFilePathHashLoader();
    assertThat(loader.get(file), not(equalTo(loader.get(fileInDirectory))));
  }

  @Test
  public void doesNotCareAboutFileContents() throws IOException {
    FilePathHashLoader loader = newFilePathHashLoader();
    HashCode hashBefore = loader.get(file);
    Files.write(file, "Goodbye!".getBytes());
    HashCode hashAfter = loader.get(file);
    assertThat(hashBefore, equalTo(hashAfter));
  }

  @Test
  public void returnsDifferentHashesIfFileIsAssumedToBeModified() throws IOException {
    assertThatChangeIsDetected(file, file);
  }

  @Test
  public void detectsChangesWithinADirectory() throws IOException {
    assertThatChangeIsDetected(fileInDirectory, directory);
  }

  @Test
  public void changesInOtherFilesDoNotAffectDirectoryHash() throws IOException {
    FilePathHashLoader baseLoader = newFilePathHashLoader();
    FilePathHashLoader modifiedLoader = newFilePathHashLoader(file);
    assertThat(baseLoader.get(directory), equalTo(modifiedLoader.get(directory)));
  }

  @Test
  public void detectsChangesBehindASymlink() throws IOException {
    Path virtualFile = cellRoot.resolve("symlink");
    Files.createSymbolicLink(virtualFile, vfs.getPath("a.txt"));
    assertThatChangeIsDetected(file, virtualFile);
    assertThatChangeIsDetected(virtualFile, file);
  }

  @Test
  public void detectsChangesBehindADirectorySymlink() throws IOException {
    Path symlink = cellRoot.resolve("symlink");
    Files.createSymbolicLink(symlink, vfs.getPath("dir"));
    assertThatChangeIsDetected(fileInDirectory, symlink);
  }

  @Test
  public void detectsChangesBehindASymlinkInADirectory() throws IOException {
    Path symlink = directory.resolve("symlink");
    Files.createSymbolicLink(symlink, vfs.getPath("../a.txt"));
    assertThatChangeIsDetected(file, directory);
  }

  @Test
  public void relativePathsAreResolvedToCellRoot() throws IOException {
    assertThatChangeIsDetected(vfs.getPath("a.txt"), file);
    assertThatChangeIsDetected(file, vfs.getPath("a.txt"));
  }

  @Test
  public void denormalizedPathsAlsoWork() throws IOException {
    assertThatChangeIsDetected(directory.resolve("../dir/a.txt"), fileInDirectory);
    assertThatChangeIsDetected(fileInDirectory, directory.resolve("../dir/a.txt"));
  }

  @Test
  public void cellRootPathDoesNotInfluenceTheHashes() throws IOException {
    FilePathHashLoader baseLoader = newFilePathHashLoader(fileInDirectory);
    HashCode fileHashCode = baseLoader.get(file);
    HashCode fileInDirectoryHashCode = baseLoader.get(fileInDirectory);
    HashCode directoryHashCode = baseLoader.get(directory);
    Path newCellRoot = vfs.getPath("/different");
    Files.move(cellRoot, newCellRoot);
    file = newCellRoot.resolve("a.txt");
    directory = newCellRoot.resolve("dir");
    fileInDirectory = directory.resolve("a.txt");
    FilePathHashLoader newLoader =
        new FilePathHashLoader(
            newCellRoot, ImmutableSet.of(fileInDirectory), /* allowSymlinks */ true);
    assertThat(newLoader.get(file), equalTo(fileHashCode));
    assertThat(newLoader.get(fileInDirectory), equalTo(fileInDirectoryHashCode));
    assertThat(newLoader.get(directory), equalTo(directoryHashCode));
  }

  private void assertThatChangeIsDetected(Path changedPath, Path checkedPath) throws IOException {
    FilePathHashLoader baseLoader = newFilePathHashLoader();
    FilePathHashLoader modifiedLoader = newFilePathHashLoader(changedPath);
    assertThat(baseLoader.get(checkedPath), not(equalTo(modifiedLoader.get(checkedPath))));
  }

  private FilePathHashLoader newFilePathHashLoader(Path... path) throws IOException {
    return new FilePathHashLoader(cellRoot, ImmutableSet.copyOf(path), /* allowSymlinks */ true);
  }
}

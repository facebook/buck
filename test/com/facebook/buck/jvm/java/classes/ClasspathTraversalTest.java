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

package com.facebook.buck.jvm.java.classes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ClasspathTraversalTest {
  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  private Map<FileLike, String> traverse(Collection<File> files) throws IOException {
    Collection<Path> paths =
        files.stream().map(File::toPath).collect(ImmutableList.toImmutableList());
    ImmutableMap.Builder<FileLike, String> completeList = ImmutableMap.builder();
    ClasspathTraverser traverser = new DefaultClasspathTraverser();
    traverser.traverse(
        new ClasspathTraversal(paths, AbsPath.of(tempDir.getRoot().toPath()), ImmutableSet.of()) {
          @Override
          public void visit(FileLike fileLike) {
            String contents;
            try {
              contents = new FileLikeCharSource(fileLike).read();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
            completeList.put(fileLike, contents);
          }
        });
    return completeList.build();
  }

  private void verifyFileLike(int expectedFiles, File... paths) throws IOException {
    int fileLikeCount = 0;
    for (Map.Entry<FileLike, String> entry : traverse(Lists.newArrayList(paths)).entrySet()) {
      assertEquals(
          "Relative file-like path mismatch",
          MorePaths.pathWithPlatformSeparators(entry.getValue()),
          MorePaths.pathWithPlatformSeparators(entry.getKey().getRelativePath()));
      fileLikeCount++;
    }
    assertEquals(expectedFiles, fileLikeCount);
  }

  @Test
  public void testDirectoryAndFile() throws IOException {
    File notADirectory = tempDir.newFile("not_a_directory.txt");
    Files.write("not_a_directory.txt", notADirectory, StandardCharsets.UTF_8);
    File yesADir = tempDir.newFolder("is_a_directory");
    Files.write("foo.txt", new File(yesADir, "foo.txt"), StandardCharsets.UTF_8);
    Files.write("bar.txt", new File(yesADir, "bar.txt"), StandardCharsets.UTF_8);
    File aSubDir = new File(yesADir, "fizzbuzz");
    assertTrue("Failed to create dir: " + aSubDir, aSubDir.mkdir());
    Files.write(
        MorePaths.pathWithPlatformSeparators("fizzbuzz/whatever.txt"),
        new File(aSubDir, "whatever.txt"),
        StandardCharsets.UTF_8);
    verifyFileLike(4, notADirectory, yesADir);
  }

  @Test
  public void testZip() throws IOException {
    String[] files = {"test/foo.txt", "bar.txt", "test/baz.txt"};
    File file = tempDir.newFile("test.zip");
    try (ZipOutputStream zipOut =
        new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
      for (String filename : files) {
        ZipEntry entry = new ZipEntry(filename);
        zipOut.putNextEntry(entry);
        zipOut.write(filename.getBytes(StandardCharsets.UTF_8));
      }
    }
    verifyFileLike(3, file);
  }
}

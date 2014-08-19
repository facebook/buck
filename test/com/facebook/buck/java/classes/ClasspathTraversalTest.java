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

package com.facebook.buck.java.classes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ClasspathTraversalTest {
  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  private static Map<FileLike, String> traverse(Collection<File> files) throws IOException {
    Collection<Path> paths = FluentIterable.from(files)
        .transform(new Function<File, Path>() {
      @Override
      public Path apply(File file) {
        return file.toPath();
      }
    }).toList();
    final ImmutableMap.Builder<FileLike, String> completeList = ImmutableMap.builder();
    ClasspathTraverser traverser = new DefaultClasspathTraverser();
    traverser.traverse(new ClasspathTraversal(paths, new ProjectFilesystem(Paths.get("."))) {
      @Override
      public void visit(FileLike fileLike) {
        String contents;
        try {
          contents = new FileLikeCharSource(fileLike).read();
        } catch (IOException e) {
          throw Throwables.propagate(e);
        }
        completeList.put(fileLike, contents);
      }
    });
    return completeList.build();
  }

  private static void verifyFileLike(int expectedFiles, File... paths) throws IOException {
    int fileLikeCount = 0;
    for (Map.Entry<FileLike, String> entry : traverse(Lists.newArrayList(paths)).entrySet()) {
      assertEquals(
          "Relative file-like path mismatch",
          entry.getValue(),
          entry.getKey().getRelativePath());
      fileLikeCount++;
    }
    assertEquals(expectedFiles, fileLikeCount);
  }

  @Test
  public void testDirectoryAndFile() throws IOException {
    File notADirectory = tempDir.newFile("not_a_directory.txt");
    Files.write("not_a_directory.txt", notADirectory, Charsets.UTF_8);
    File yesADir = tempDir.newFolder("is_a_directory");
    Files.write("foo.txt", new File(yesADir, "foo.txt"), Charsets.UTF_8);
    Files.write("bar.txt", new File(yesADir, "bar.txt"), Charsets.UTF_8);
    File aSubDir = new File(yesADir, "fizzbuzz");
    assertTrue("Failed to create dir: " + aSubDir, aSubDir.mkdir());
    Files.write("fizzbuzz/whatever.txt", new File(aSubDir, "whatever.txt"),
        Charsets.UTF_8);
    verifyFileLike(4, notADirectory, yesADir);
  }

  @Test
  public void testZip() throws IOException {
    String[] files = { "test/foo.txt", "bar.txt", "test/baz.txt" };
    File file = tempDir.newFile("test.zip");
    try (ZipOutputStream zipOut = new ZipOutputStream(
        new BufferedOutputStream(new FileOutputStream(file)))) {
      for (String filename : files) {
        ZipEntry entry = new ZipEntry(filename);
        zipOut.putNextEntry(entry);
        zipOut.write(filename.getBytes(Charsets.UTF_8));
      }
    }
    verifyFileLike(3, file);
  }
}

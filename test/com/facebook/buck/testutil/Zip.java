/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * An abstraction for dealing with Zip files. Use it within a try-with-resources block for maximum
 * fun and profit. This differs from {@link java.util.zip.ZipFile} by providing a mechanism to add
 * content to the zip and by not providing a way of extracting individual entries (just the names
 * of the entries).
 */
public class Zip implements AutoCloseable {

  // Java 7 introduced an abstraction for modeling file systems. One of the implementations that
  // ships with the JRE is one that handles Zip files. This allows us to work on zip files directly
  // without needing to write the contents to an intermediate directory.
  // See: http://docs.oracle.com/javase/7/docs/technotes/guides/io/fsp/zipfilesystemprovider.html
  private final FileSystem fs;
  private final Path root;

  /**
   * Open the zip file for reading and/or writing. Note that this constructor will create
   * {@code zip} if {@code forWriting} is true and the file does not exist.
   *
   * @param zip The zip file to operate on. The name must end with ".zip" or ".jar".
   * @param forWriting Whether the zip file should be opened for writing or not.
   * @throws IOException Should something terrible occur.
   */
  public Zip(File zip, boolean forWriting) throws IOException {
    assertTrue("zip name must end with .zip for file type detection to work",
        zip.getName().endsWith(".zip") || zip.getName().endsWith(".jar"));

    URI uri = URI.create("jar:" + zip.toURI());
    fs = FileSystems.newFileSystem(
        uri, ImmutableMap.of("create", String.valueOf(forWriting)));

    root = Iterables.getFirst(fs.getRootDirectories(), null);
    assertNotNull("Unable to determine root of file system: " + fs, root);
  }

  public void add(String fileName, byte[] contents) throws IOException {
    Path zipPath = fs.getPath(root.toString(), fileName);
    if (Files.notExists(zipPath.getParent())) {
      Files.createDirectory(zipPath.getParent());
    }
    Files.write(zipPath, contents, CREATE, WRITE);
  }

  public void add(String fileName, String contents) throws IOException {
    add(fileName, contents.getBytes());
  }

  public void addDir(String dirName) throws IOException {
    Path zipPath = fs.getPath(root.toString(), dirName);
    if (Files.notExists(zipPath)) {
      Files.createDirectory(zipPath);
    }
  }

  public Set<String> getDirNames() throws IOException {
    final ImmutableSet.Builder<String> contents = ImmutableSet.builder();
    Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            // Skip the leading "/" from the path.
            contents.add(dir.toString().substring(1));
            return FileVisitResult.CONTINUE;
          }
        });
    return contents.build();
  }

  public Set<String> getFileNames() throws IOException {
    final ImmutableSet.Builder<String> contents = ImmutableSet.builder();
    Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        // Skip the leading "/" from the path.
        contents.add(file.toString().substring(1));
        return FileVisitResult.CONTINUE;
      }
    });
    return contents.build();
  }

  public byte[] readFully(String fileName) throws IOException {
    Path resolved = root.resolve(fileName);

    return Files.readAllBytes(resolved);
  }

  @Override
  public void close() throws IOException {
    fs.close();
  }
}

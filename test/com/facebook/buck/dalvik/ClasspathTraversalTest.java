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

package com.facebook.buck.dalvik;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCodes;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.zip.CRC32;
import java.util.zip.Checksum;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ClasspathTraversalTest {
  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  private static Collection<FileLike> traverse(Collection<File> paths) throws IOException {
    final ImmutableList.Builder<FileLike> completeList = ImmutableList.builder();
    ClasspathTraverser traverser = new DefaultClasspathTraverser();
    traverser.traverse(new ClasspathTraversal(paths) {
      @Override
      public void visit(FileLike fileLike) {
        completeList.add(fileLike);
      }
    });
    return completeList.build();
  }

  private static void verifyFileLike(int expectedFiles, File... paths) throws IOException {
    int fileLikeCount = 0;
    for (FileLike fileLike : traverse(Lists.newArrayList(paths))) {
      String contents = CharStreams.toString(
          CharStreams.newReaderSupplier(new FileLikeInputSupplier(fileLike),
              Charsets.UTF_8));
      assertEquals("Relative file-like path mismatch", contents, fileLike.getRelativePath());
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
    ZipOutputStream zipOut = new ZipOutputStream(
        new BufferedOutputStream(new FileOutputStream(file)));
    try {
      for (String filename : files) {
        ZipEntry entry = new ZipEntry(filename);
        zipOut.putNextEntry(entry);
        zipOut.write(filename.getBytes(Charsets.UTF_8));
      }
    } finally {
      zipOut.close();
    }
    verifyFileLike(3, file);
  }

  private static long getChecksum(Checksum algorithm, String data, Charset charset) {
    byte[] dataBytes = data.getBytes(charset);
    algorithm.update(dataBytes, 0, dataBytes.length);
    return algorithm.getValue();
  }

  @Test
  public void testZipHashing() throws IOException {
    String contents = "test crc32";
    final long contentsCrc32 = getChecksum(new CRC32(), contents, Charsets.UTF_8);
    File file = tempDir.newFile("test.zip");
    ZipOutputStream zipOut = new ZipOutputStream(
        new BufferedOutputStream(new FileOutputStream(file)));
    try {
      ZipEntry entry = new ZipEntry("foo.txt");
      zipOut.putNextEntry(entry);
      zipOut.write(contents.getBytes(Charsets.UTF_8));
    } finally {
      zipOut.close();
    }

    Collection<FileLike> entries = traverse(Collections.singleton(file));
    assertEquals(1, entries.size());
    FileLike entry = Iterables.getFirst(entries, null);
    assertEquals("CRC of input text should equal FileLike#fastHash",
        HashCodes.fromLong(contentsCrc32), entry.fastHash());
  }
}

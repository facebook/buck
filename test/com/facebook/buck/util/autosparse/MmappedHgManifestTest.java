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

package com.facebook.buck.util.autosparse;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

public class MmappedHgManifestTest {
  @ClassRule public static TemporaryFolder tempFolder = new TemporaryFolder();

  // NULL separator plus 40-character hash value
  private static String SEP_HASH = "\0000102030405060708090a0b0c0d0e0f1011121314";

  @Rule public final ExpectedException exception = ExpectedException.none();

  @Test
  public void testEmpty() throws IOException {
    MmappedHgManifest emptyMmap = makeOne("");
    Stream<MmappedHgManifest.ManifestEntry> entries = emptyMmap.loadDirectory("foo");
    assertEquals(entries.count(), 0);
  }

  @Test
  public void testAllEntriesDeleted() throws IOException {
    MmappedHgManifest mmap =
        makeOne(
            String.join(
                "\n",
                "foo/bar/baz" + SEP_HASH,
                "foo/bar/spam" + SEP_HASH,
                // deletion entries
                "foo/bar/baz" + SEP_HASH + "r",
                "foo/bar/spam" + SEP_HASH + "r\n"));

    Stream<MmappedHgManifest.ManifestEntry> entries = mmap.loadDirectory("foo");
    assertEquals(entries.count(), 0);
  }

  @Test
  public void testSomeEntriesDeleted() throws IOException {
    MmappedHgManifest mmap =
        makeOne(
            String.join(
                "\n",
                "foo/bar/baz" + SEP_HASH,
                "foo/bar/bam" + SEP_HASH,
                "foo/bar/spam" + SEP_HASH,
                // deletion entries
                "foo/bar/bam" + SEP_HASH + "r\n"));

    Stream<MmappedHgManifest.ManifestEntry> entries = mmap.loadDirectory("foo");
    assertEquals(
        entries.map(e -> e.getFilename()).collect(Collectors.toList()),
        ImmutableList.of("foo/bar/baz", "foo/bar/spam"));
  }

  @Test
  public void matchAtStart() throws IOException {
    MmappedHgManifest mmap =
        makeOne(
            String.join(
                "\n",
                "foo/bar/file1" + SEP_HASH,
                "foo/bar/file2" + SEP_HASH + "x",
                "foo/bar/file3" + SEP_HASH + "l",
                "foo/baz/not_matched" + SEP_HASH,
                "foo/spam/also_not_matched" + SEP_HASH + "\n"));

    Stream<MmappedHgManifest.ManifestEntry> entries = mmap.loadDirectory("foo/bar");
    assertEquals(
        entries.map(e -> e.getFilename()).collect(Collectors.toList()),
        ImmutableList.of("foo/bar/file1", "foo/bar/file2", "foo/bar/file3"));

    entries = mmap.loadDirectory("foo/bar");
    assertEquals(
        entries.map(e -> e.getFlag()).collect(Collectors.toList()), ImmutableList.of("", "x", "l"));
  }

  @Test
  public void matchAtEnd() throws IOException {
    MmappedHgManifest mmap =
        makeOne(
            String.join(
                "\n",
                "foo/aaz/not_matched" + SEP_HASH,
                "foo/azz/also_not_matched" + SEP_HASH,
                "foo/bar/file1" + SEP_HASH,
                "foo/bar/file2" + SEP_HASH + "x",
                "foo/bar/file3" + SEP_HASH + "l" + "\n"));

    Stream<MmappedHgManifest.ManifestEntry> entries = mmap.loadDirectory("foo/bar");
    assertEquals(
        entries.map(e -> e.getFilename()).collect(Collectors.toList()),
        ImmutableList.of("foo/bar/file1", "foo/bar/file2", "foo/bar/file3"));

    entries = mmap.loadDirectory("foo/bar");
    assertEquals(
        entries.map(e -> e.getFlag()).collect(Collectors.toList()), ImmutableList.of("", "x", "l"));
  }

  @Test
  public void exactMatch() throws IOException {
    MmappedHgManifest mmap =
        makeOne(
            String.join(
                "\n",
                "foo/aaz/not_matched" + SEP_HASH,
                "foo/azz/also_not_matched" + SEP_HASH,
                "foo/bar/file1" + SEP_HASH,
                "foo/bar/file2" + SEP_HASH + "x",
                "foo/bar/file3" + SEP_HASH + "l" + "\n"));

    Stream<MmappedHgManifest.ManifestEntry> entries = mmap.loadDirectory("foo/bar/file1");
    assertEquals(
        entries.map(e -> e.getFilename()).collect(Collectors.toList()),
        ImmutableList.of("foo/bar/file1"));
  }

  @Test
  public void exactMatchDeleted() throws IOException {
    MmappedHgManifest mmap =
        makeOne(
            String.join(
                "\n",
                "foo/aaz/not_matched" + SEP_HASH,
                "foo/azz/also_not_matched" + SEP_HASH,
                "foo/bar/file1" + SEP_HASH,
                "foo/bar/file2" + SEP_HASH + "x",
                "foo/bar/file3" + SEP_HASH + "l",
                "foo/bar/file1" + SEP_HASH + "r" + "\n"));

    Stream<MmappedHgManifest.ManifestEntry> entries = mmap.loadDirectory("foo/bar/file1");
    assertEquals(entries.count(), 0);
  }

  @Test
  public void testLargerChunked() throws IOException {
    // Generate a large number of entries, 26 ** 3 lines
    List<String> letters = Arrays.asList("abcdefghijklmnopqrstuvwxyz".split(""));
    Iterator<String> flags =
        Iterables.cycle(ImmutableList.of("", "", "", "x", "", "", "", "l", "", "", "")).iterator();
    String lines =
        String.join(
            "",
            Lists.cartesianProduct(Collections.nCopies(3, letters))
                .stream()
                .map(
                    lst ->
                        lst.stream()
                            .map(segment -> segment + "segment")
                            .collect(Collectors.toList()))
                .map(lst -> String.join("/", lst) + SEP_HASH + flags.next() + "\n")
                .collect(Collectors.toList()));
    MmappedHgManifest mmap = makeOne(lines, lines.length() / 10);

    Stream<MmappedHgManifest.ManifestEntry> entries;

    // At the start
    entries = mmap.loadDirectory("asegment/asegment");
    assertEquals(entries.count(), 26);

    // Somewhere in the middle
    entries = mmap.loadDirectory("lsegment/qsegment");
    assertEquals(entries.count(), 26);

    // At the end
    entries = mmap.loadDirectory("zsegment/zsegment");
    assertEquals(entries.count(), 26);
  }

  @Test
  public void pathPrefixEdgecase() throws IOException {
    // When bisection ends up on a path that has a matching prefix (without the path separator)
    // make sure we recognise it as a path that preceeds the target dir.
    MmappedHgManifest mmap =
        makeOne(
            String.join(
                "\n",
                "foo/bar-prefixed/path1" + SEP_HASH,
                "foo/bar-prefixed/path2" + SEP_HASH,
                "foo/bar-prefixed/path3" + SEP_HASH,
                "foo/bar/not-prefixed/file1" + SEP_HASH,
                "foo/bar/not-prefixed/file2" + SEP_HASH,
                ""));

    Stream<MmappedHgManifest.ManifestEntry> entries = mmap.loadDirectory("foo/bar");
    assertEquals(
        entries.map(e -> e.getFilename()).collect(Collectors.toList()),
        ImmutableList.of("foo/bar/not-prefixed/file1", "foo/bar/not-prefixed/file2"));
  }

  private MmappedHgManifest makeOne(String manifestData, int chunk_size) throws IOException {
    Path manifestFilePath = Files.createTempFile(tempFolder.getRoot().toPath(), "rawmanifest", "");
    Files.write(manifestFilePath, manifestData.getBytes("UTF-8"));
    return new MmappedHgManifest(manifestFilePath, chunk_size);
  }

  private MmappedHgManifest makeOne(String manifestData) throws IOException {
    return makeOne(manifestData, MmappedHgManifest.DEFAULT_CHUNK_SIZE);
  }
}

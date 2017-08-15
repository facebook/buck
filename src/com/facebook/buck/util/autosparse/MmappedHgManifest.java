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

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Memmory-map over a raw manifest dump, giving access to an iterator of files given a directory
 * prefix.
 *
 * <p>The file consists of a series of filename\0hash[flag]\n lines. Filenames are variable in
 * length, the hash is 40 hex characters long and ignored here, followed by a single-character
 * optional flag The lines are separated by \n newline characters, the delimiter between filename
 * and hash is a NUL. Entries are sorted lexicographically.
 *
 * <p>Directories are found by using bisection. To help performance on large manifests, an initial
 * index is created to bisect into the right 'chunk' of the file, based on the chunkSize value
 * passed in.
 */
public class MmappedHgManifest {
  private static final Logger LOG = Logger.get(AutoSparseState.class);

  private static final short HASH_LENGTH = 40;
  private static final long MAX_FILE_SIZE = 2147483647; // 2GB, or 2 ** 31 - 1

  // Bytes we look for in the manifest data
  private static final byte NEWLINE = (byte) '\n';
  private static final byte NUL = (byte) 0;
  private static final byte REMOVED = (byte) 'r';

  // Create an in memory index to bisect on covering this much of the manifest at a time:
  @VisibleForTesting static final int DEFAULT_CHUNK_SIZE = 256 * 1024;

  private final Charset encoding;
  private int size;
  private int lastIndex;
  private final MappedByteBuffer mmap;
  private final ImmutableSortedSet<ChunkIndexEntry> chunkIndex;
  private final ImmutableSet<String> deletedPaths;

  public MmappedHgManifest(Path rawManifestPath, int chunkSize) throws IOException {
    // Here is to hoping this is the correct codec on all platforms; Mercurial
    // doesn't care what the filesystem encoding is and just stores the raw bytes.
    String systemEncoding = System.getProperty("file.encoding");
    this.encoding =
        Charset.availableCharsets().getOrDefault(systemEncoding, StandardCharsets.UTF_8);

    try (FileChannel fileChannel = FileChannel.open(rawManifestPath); ) {
      long longSize = fileChannel.size();

      if (longSize > MAX_FILE_SIZE) {
        // MappedByteBuffer can only handle int positions, so we can only address 2GB with a single
        // mmap. Reaching larger manifest sizes would require using multiple mmap objects and
        // added complexity.
        throw new IOException("Unable to load a manifest > 2GB");
      }
      mmap = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, longSize);
      size = (int) longSize;

      Pair<Integer, ImmutableSet<String>> sizeAndDeleted = loadDeleted(size);
      lastIndex = sizeAndDeleted.getFirst().intValue();
      deletedPaths = sizeAndDeleted.getSecond();

      // build an index on which to do in-memory bisection before bisecting the mmap itself
      ImmutableSortedSet.Builder<ChunkIndexEntry> indexBuilder = ImmutableSortedSet.naturalOrder();

      for (int pos = chunkSize; pos < size; pos += chunkSize) {
        int start = findEntryStart(pos);
        int end = find(NUL, start);
        indexBuilder.add(new ChunkIndexEntry(getString(start, end), start));
      }

      chunkIndex = indexBuilder.build();
      LOG.info(
          "Manifest mmap loaded, %d chunks, %d deleted paths, %d size",
          chunkIndex.size(), deletedPaths.size(), size);
    }
  }

  public MmappedHgManifest(Path rawManifestPath) throws IOException {
    this(rawManifestPath, DEFAULT_CHUNK_SIZE);
  }

  private Pair<Integer, ImmutableSet<String>> loadDeleted(int size) {
    ImmutableSet<String> deleted = ImmutableSet.of();
    // A number of working copy deletion entries are appended to the end; load these first and
    // set lastIndex to prevent access to these later on. We'll filter the manifest to pretent these
    // deleted entries don't exist.
    //
    // Deletion entries have the optional flag set to "r", so bisect from the end until we
    // find an entry with the flag set and a preceding entry without the flag set.
    // We only need to do this work if the second-last byte is "r"!
    // The minimum size check ensures there is at least enough room in the file for one entry.
    if (size > HASH_LENGTH + 3 && mmap.get(size - 2) == REMOVED) {
      int start = -1;
      int end = size;
      int step = 256;
      Iterator<ManifestEntry> deletedIterator = Collections.emptyIterator();

      while (start < end) {
        int mid = start == -1 ? end - step : (start + end) / 2;
        if (mid <= 0) {
          // never go further back than the very start
          start = 0;
          mid = (start + end) / 2;
        }
        int entryStart = findEntryStart(mid);
        int entryEnd = find(NEWLINE, entryStart);
        if (mmap.get(entryEnd - 1) == REMOVED) {
          // Hit a deletion entry. If the preceding entry is not a deletion entry we found the
          // start of the deletion section.
          byte prevFlag = NUL;
          if (entryStart > 1) {
            prevFlag = mmap.get(entryStart - 2);
          }
          if (prevFlag != REMOVED) {
            // bingo, found our starting point. Load all deleted entries from here, and
            // set the manifest size to exclude the deletion entries.
            deletedIterator = new ManifestEntryIterator(this, "", entryStart);
            size = entryStart;
            break;
          } else {
            // need to seek back further
            end = entryStart;
            if (start == -1) {
              step *= 2;
            }
          }
        } else {
          // too far back, go forward again
          start = entryEnd + 1;
        }
      }

      deleted = RichStream.from(deletedIterator).map(ManifestEntry::getFilename).toImmutableSet();
    }
    return new Pair<>(size, deleted);
  }

  /**
   * Produce a stream over all entries that start with the pathname directory
   *
   * @param pathname: prefix to search for; the iterator yields all entries within this path.
   * @return Stream over @link MmappedHgManifest.ManifestEntry objects
   */
  public Stream<ManifestEntry> loadDirectory(String pathname) {
    // edgecase check, exact match for a deleted entry first
    if (deletedPaths.contains(pathname)) {
      return Stream.empty();
    }

    String dirname = pathname.endsWith("/") ? pathname : pathname.concat("/");
    // narrow down start and end to a chunk based on cached indices; defaulting to 0 and lastIndex
    ChunkIndexEntry testEntry = new ChunkIndexEntry(dirname, -1);
    int start = Optional.ofNullable(chunkIndex.floor(testEntry)).map(f -> f.getIndex()).orElse(0);
    int end =
        Optional.ofNullable(chunkIndex.higher(testEntry)).map(h -> h.getIndex()).orElse(lastIndex);

    while (start < end) {
      int mid = (start + end) / 2;

      int entryStart = findEntryStart(mid);
      int entryEnd = find(NUL, entryStart);
      String entryName = getString(entryStart, entryEnd);

      if (entryName.startsWith(dirname)) {
        // test if this is the first entry with the right prefix; test the preceding entry
        String prevEntry = null;
        if (entryStart > 0) {
          int prevStart = findEntryStart(entryStart - 2);
          prevEntry = getString(prevStart, find(NUL, prevStart));
        }
        if (prevEntry == null || !prevEntry.startsWith(dirname)) {
          // bingo, found our starting point. Iterate from here
          return RichStream.from(new ManifestEntryIterator(this, dirname, entryStart))
              .filter(entry -> !deletedPaths.contains(entry.getFilename()));
        } else {
          // need to go further back
          end = entryStart;
        }
      } else {
        if (entryName.equals(pathname)) {
          // edge-case, not a directory but a single entry
          String flag = "";
          byte flagByte = mmap.get(entryEnd + 1 + HASH_LENGTH);
          if (flagByte != NEWLINE) {
            flag = Character.toString((char) flagByte);
          }
          // Can't be deleted, as we tested for that case right at the start of this function
          return Stream.of(new ManifestEntry(entryName, flag));
        } else if (entryName.compareTo(dirname) > 0) {
          end = entryStart;
        } else {
          // add hash and delimiter length, then account for an optional flag
          entryEnd += HASH_LENGTH + 1;
          start = entryEnd + (mmap.get(entryEnd) == NEWLINE ? 1 : 2);
        }
      }
    }
    return Stream.empty();
  }

  /**
   * Search backwards to find manifest entry start
   *
   * @param pos Position to start searching from
   * @return starting position of a manifest entry
   */
  private int findEntryStart(int pos) {
    while (pos >= 0 && mmap.get(pos) != NEWLINE) {
      pos -= 1;
    }
    return pos + 1;
  }

  /**
   * Search forwards to find position of byte <code>b</code>.
   *
   * @return the position of a given character. Can be equal to <code>pos</code>.
   */
  private int find(byte b, int pos) {
    while (pos < size && mmap.get(pos) != b) {
      pos += 1;
    }
    return pos;
  }

  /**
   * Read bytes in the range <code>[start, end)</code>.
   *
   * @return a decoded String value
   */
  private String getString(int start, int end) {
    byte[] buffer = new byte[end - start];
    mmap.position(start);
    mmap.get(buffer, 0, end - start);
    return new String(buffer, encoding);
  }

  public final class ManifestEntry {
    private final String filename;
    private final String flag;

    private ManifestEntry(String filename, String flag) {
      this.filename = filename;
      this.flag = flag;
    }

    public String getFilename() {
      return filename;
    }

    public String getFlag() {
      return flag;
    }
  }

  /**
   * Iterator over a directory (and all subdirectories) in the manifest given a dirname prefix
   *
   * <p>Relies on the first entry already having been found. We load the *future* entry so we know
   * ahead of time if there is a next element.
   *
   * <p><code>pos</code> is the start of the entry to load after yielding the current or -1 when
   * there is nothing to yield anymore.
   */
  public final class ManifestEntryIterator implements Iterator<ManifestEntry> {
    final MmappedHgManifest manifest;
    private String dirname;
    private int pos = -1;
    @Nullable private ManifestEntry next = null;

    private ManifestEntryIterator(MmappedHgManifest manifest, String dirname, int pos) {
      this.manifest = manifest;
      this.dirname = dirname;
      this.pos = pos;
      findNext();
    }

    private void findNext() {
      if (pos >= manifest.size) {
        pos = -1;
        return;
      }

      int end = find(NUL, pos);
      String entryName = manifest.getString(pos, end);
      if (!entryName.startsWith(dirname)) {
        // Directory done, next entry is not part of it anymore
        pos = -1;
        return;
      }

      pos = end + HASH_LENGTH + 1;
      byte flagByte = manifest.mmap.get(pos);
      String flag = "";
      if (flagByte != NEWLINE) {
        pos += 1;
        flag = Character.toString((char) flagByte);
      }
      next = new ManifestEntry(entryName, flag);
      pos += 1;
    }

    @Override
    public boolean hasNext() {
      return pos != -1;
    }

    @Override
    public ManifestEntry next() throws NoSuchElementException {
      if (pos == -1 || next == null) {
        throw new NoSuchElementException();
      }
      ManifestEntry result = next;
      findNext();
      return result;
    }
  }

  /**
   * starting index into the file for a given file path, used to quickly focus on the right area of
   * the file.
   */
  private class ChunkIndexEntry implements Comparable<ChunkIndexEntry> {
    final String file;
    final int index;

    ChunkIndexEntry(String file, int index) {
      this.file = file;
      this.index = index;
    }

    int getIndex() {
      return index;
    }

    @Override
    public int compareTo(ChunkIndexEntry o) {
      return this.file.compareTo(o.file);
    }
  }
}

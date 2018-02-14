/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain.elf;

import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ElfStringTable {

  private ElfStringTable() {}

  private static ImmutableList<Integer> writeStringTable(
      Iterable<Entry> entries, OutputStream output) throws IOException {

    // Build a list from the original input strings storing them with the index of their original
    // input index.
    List<Pair<Integer, Entry>> originalPositionsAndEntries = new ArrayList<>();
    int index = 0;
    for (Entry entry : entries) {
      originalPositionsAndEntries.add(new Pair<>(index++, entry));
    }

    // Sort the entries by the "ends with" comparator, so that strings appear immediately before
    // strings that end with.
    originalPositionsAndEntries.sort(
        (o1, o2) -> Entry.COMMON_SUFFIXES.compare(o1.getSecond(), o2.getSecond()));

    // The size of the output string table.
    int size = 0;

    // Initial zero byte is written for all tables.
    output.write(0);
    size += 1;

    // Array maintaining the new indices in the new string table of the input strings, in the order
    // that the input strings were given.
    Integer[] indices = new Integer[originalPositionsAndEntries.size()];

    // Walk over the input entries and write them out to the new string table.
    Entry previousEntry = null;
    for (Pair<Integer, Entry> ent : originalPositionsAndEntries) {
      Entry entry = ent.getSecond();
      int newIndex;
      if (entry.len == 0) {
        newIndex = 0;
      } else {

        // If this is the first entry, or the previous string doesn't end with the current one, we
        // have to write out a new string entry to the output stream.
        if (previousEntry == null || !previousEntry.endsWith(entry)) {
          output.write(entry.data, entry.offset, entry.len);
          output.write(0);
          size += entry.len + 1;
          previousEntry = entry;
        }

        newIndex = size - 1 - entry.len;
      }

      // Add the new string index.
      indices[ent.getFirst()] = newIndex;
    }

    return ImmutableList.copyOf(indices);
  }

  private static int getLengthForStringTableAndOffset(byte[] data, int offset) {
    int index = offset;
    while (data[index] != 0) {
      index++;
    }
    return index - offset;
  }

  /**
   * Writes a string table from null terminated byte strings described by the byte array and
   * indices.
   */
  public static ImmutableList<Integer> writeStringTableFromStringTable(
      byte[] data, Iterable<Integer> indices, OutputStream output) throws IOException {
    return writeStringTable(
        RichStream.from(indices)
            .map(index -> new Entry(data, index, getLengthForStringTableAndOffset(data, index)))
            .toImmutableList(),
        output);
  }

  /**
   * Writes a string table from the given strings.
   *
   * @return a list of offsets into the written string table for the input strings (where each
   *     offset positionally corresponds to a string in the input strings iterable).
   */
  public static ImmutableList<Integer> writeStringTableFromStrings(
      Iterable<String> strings, OutputStream output) throws IOException {
    return writeStringTable(
        RichStream.from(strings).map(s -> new Entry(s.getBytes(Charsets.UTF_8))).toImmutableList(),
        output);
  }

  private static class Entry {

    /**
     * A comparator that sorts entries so that an entry immediately precedes any entry it ends with.
     * This makes it simple to merge suffix strings when writing out the string table.
     */
    private static final Comparator<Entry> COMMON_SUFFIXES =
        (o1, o2) -> {
          int idx1 = o1.offset + o1.len - 1;
          int idx2 = o2.offset + o2.len - 1;
          while (true) {
            if (idx1 < o1.offset && idx2 < o2.offset) {
              return 0;
            } else if (idx1 < o1.offset) {
              return 1;
            } else if (idx2 < o2.offset) {
              return -1;
            } else {
              int cmp = Byte.compare(o1.data[idx1], o2.data[idx2]);
              if (cmp != 0) {
                return cmp;
              }
            }
            idx1--;
            idx2--;
          }
        };

    public final byte[] data;
    public final int offset;
    public final int len;

    private Entry(byte[] data, int offset, int len) {
      this.data = data;
      this.offset = offset;
      this.len = len;
    }

    private Entry(byte[] data) {
      this(data, 0, data.length);
    }

    public boolean endsWith(Entry other) {
      if (other.len > len) {
        return false;
      }
      for (int idx = offset + (len - other.len), oidx = other.offset;
          idx < offset + len;
          idx++, oidx++) {
        if (data[idx] != other.data[oidx]) {
          return false;
        }
      }
      return true;
    }

    @Override
    public String toString() {
      return new String(data, offset, len, Charsets.UTF_8);
    }
  }
}

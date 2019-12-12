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

package com.facebook.buck.cxx.toolchain.elf;

import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ElfStringTableTest {

  @Test
  public void multipleStrings() throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImmutableList<Integer> indices =
        ElfStringTable.writeStringTableFromStrings(ImmutableList.of("hello", "world"), output);
    byte[] table = output.toByteArray();
    assertThat(table.length, Matchers.equalTo(13));
    assertThat(indices, Matchers.containsInAnyOrder(1, 7));
    assertThat(get(table, indices.get(0)), Matchers.equalTo("hello"));
    assertThat(get(table, indices.get(1)), Matchers.equalTo("world"));
  }

  @Test
  public void mergeSuffixString() throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImmutableList<Integer> indices =
        ElfStringTable.writeStringTableFromStrings(ImmutableList.of("ello", "hello"), output);
    byte[] table = output.toByteArray();
    assertThat(table.length, Matchers.equalTo(7));
    assertThat(indices, Matchers.contains(2, 1));
    assertThat(get(table, indices.get(0)), Matchers.equalTo("ello"));
    assertThat(get(table, indices.get(1)), Matchers.equalTo("hello"));
  }

  @Test
  public void identicalStrings() throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImmutableList<Integer> indices =
        ElfStringTable.writeStringTableFromStrings(ImmutableList.of("hello", "hello"), output);
    byte[] table = output.toByteArray();
    assertThat(table.length, Matchers.equalTo(7));
    assertThat(indices, Matchers.contains(1, 1));
    assertThat(get(table, indices.get(0)), Matchers.equalTo("hello"));
    assertThat(get(table, indices.get(1)), Matchers.equalTo("hello"));
  }

  private String get(byte[] data, int offset) {
    StringBuilder builder = new StringBuilder();
    while (data[offset] != 0) {
      builder.append((char) data[offset]);
      offset++;
    }
    return builder.toString();
  }
}

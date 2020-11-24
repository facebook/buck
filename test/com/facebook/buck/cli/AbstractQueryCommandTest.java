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

package com.facebook.buck.cli;

import static org.junit.Assert.assertThat;

import com.facebook.buck.util.MoreStringsForTests;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Test;

public class AbstractQueryCommandTest {

  private void testPrintListImpl(ImmutableList<String> list) {
    String expected = list.stream().map(s -> s + "\n").collect(Collectors.joining());
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    AbstractQueryCommand.printListImpl(list, s -> s, new PrintStream(byteArrayOutputStream));
    assertThat(
        new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8),
        MoreStringsForTests.equalToIgnoringPlatformNewlines(expected));
  }

  @Test
  public void printListImpl() {
    testPrintListImpl(ImmutableList.of());
    testPrintListImpl(ImmutableList.of("aa"));
    testPrintListImpl(ImmutableList.of("aa", "bbb"));
    testPrintListImpl(
        IntStream.range(0, 10000)
            .mapToObj(Integer::toString)
            .collect(ImmutableList.toImmutableList()));
    testPrintListImpl(
        IntStream.range(0, 12345)
            .mapToObj(Integer::toString)
            .collect(ImmutableList.toImmutableList()));
    testPrintListImpl(
        IntStream.range(0, 123456)
            .mapToObj(Integer::toString)
            .collect(ImmutableList.toImmutableList()));
  }
}

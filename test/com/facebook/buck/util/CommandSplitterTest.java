/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

public class CommandSplitterTest {

  @Test
  public void emptyArgumentList() {
    CommandSplitter commandSplitter = new CommandSplitter(ImmutableList.of("some", "command"));

    assertEquals(
        ImmutableList.<ImmutableList<String>>of(),
        commandSplitter.getCommandsForArguments(ImmutableList.<String>of()));
  }

  @Test
  public void singleArgument() {
    CommandSplitter commandSplitter = new CommandSplitter(ImmutableList.of("some", "command"));

    assertEquals(
        ImmutableList.of(
            ImmutableList.of("some", "command", "argument1")),
        commandSplitter.getCommandsForArguments(ImmutableList.of("argument1")));
  }

  @Test
  public void doesNotSplitShortArgumentLists() {
    CommandSplitter commandSplitter = new CommandSplitter(ImmutableList.of("some", "command"), 100);

    assertEquals(
        ImmutableList.of(
            ImmutableList.of("some", "command", "argument1", "argument2", "argument3")),
        commandSplitter.getCommandsForArguments(
            ImmutableList.of("argument1", "argument2", "argument3")));
  }

  @Test
  public void splitsLongArgumentLists() {
    CommandSplitter commandSplitter = new CommandSplitter(ImmutableList.of("some", "command"), 25);

    assertEquals(
        ImmutableList.of(
            ImmutableList.of("some", "command", "argument1"),
            ImmutableList.of("some", "command", "argument2"),
            ImmutableList.of("some", "command", "argument3")),
        commandSplitter.getCommandsForArguments(
            ImmutableList.of("argument1", "argument2", "argument3")));
  }

  @Test
  public void canGenerateMultipleCommandsWithMultipleArgumentsEach() {
    CommandSplitter commandSplitter = new CommandSplitter(ImmutableList.of("some", "command"), 33);

    assertEquals(
        ImmutableList.of(
            ImmutableList.of("some", "command", "argument1", "argument2"),
            ImmutableList.of("some", "command", "argument3", "argument4"),
            ImmutableList.of("some", "command", "argument5")),
        commandSplitter.getCommandsForArguments(
            ImmutableList.of("argument1", "argument2", "argument3", "argument4", "argument5")));
  }

  @Test
  public void followsTheLimitExactly() {
    CommandSplitter commandSplitter = new CommandSplitter(ImmutableList.of("a"), 7);

    assertEquals(
        ImmutableList.of(
            ImmutableList.of("a", "b", "cc"),
            ImmutableList.of("a", "d", "e"),
            ImmutableList.of("a", "f")),
        commandSplitter.getCommandsForArguments(
            ImmutableList.of("b", "cc", "d", "e", "f")));
  }

  @Test
  public void exceedsLimitIfASingleArgumentIsTooLong() {
    CommandSplitter commandSplitter = new CommandSplitter(ImmutableList.of("some", "command"), 20);

    assertEquals(
        ImmutableList.of(
            ImmutableList.of("some", "command", "a", "b"),
            ImmutableList.of("some", "command", "this-is-a-very-long-argument"),
            ImmutableList.of("some", "command", "this-too-is-a-very-long-argument"),
            ImmutableList.of("some", "command", "c", "d")),
        commandSplitter.getCommandsForArguments(
            ImmutableList.of(
                "a",
                "b",
                "this-is-a-very-long-argument",
                "this-too-is-a-very-long-argument",
                "c",
                "d")));
  }

}

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

package com.facebook.buck.cli;

import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.BuckArgsMethods;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class BuckArgsMethodsTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testArgFileExpansion() throws IOException {
    assertThat(
        BuckArgsMethods.expandAtFiles(new String[] {"arg1"}, tmp.getRoot()),
        Matchers.arrayContaining("arg1"));

    assertThat(
        BuckArgsMethods.expandAtFiles(new String[] {"arg1", "arg@a"}, tmp.getRoot()),
        Matchers.arrayContaining("arg1", "arg@a"));

    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(new String[] {"arg1", "@" + arg.toString()}, tmp.getRoot()),
        Matchers.arrayContaining("arg1", "arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            new String[] {"arg1", "@" + arg.toString(), "arg4"}, tmp.getRoot()),
        Matchers.arrayContaining("arg1", "arg2", "arg3", "arg4"));

    assertThat(
        BuckArgsMethods.expandAtFiles(new String[] {"@" + arg.toString()}, tmp.getRoot()),
        Matchers.arrayContaining("arg2", "arg3"));

    Path arg4 = tmp.newFile("argsfile4");
    Files.write(arg4, ImmutableList.of("arg4"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            new String[] {"arg1", "@" + arg.toString(), "@" + arg4.toString()}, tmp.getRoot()),
        Matchers.arrayContaining("arg1", "arg2", "arg3", "arg4"));

    Path argWithSpace = tmp.newFile("argsfile_space");
    Files.write(argWithSpace, ImmutableList.of("arg "));
    assertThat(
        BuckArgsMethods.expandAtFiles(new String[] {"@" + argWithSpace.toString()}, tmp.getRoot()),
        Matchers.arrayContaining("arg "));
  }

  @Test
  public void testArgsFiltering() {
    assertThat(
        BuckArgsMethods.filterArgs(
            ImmutableList.of("arg1", "--option1", "value1", "arg2"), ImmutableSet.of()),
        Matchers.contains("arg1", "--option1", "value1", "arg2"));

    assertThat(
        BuckArgsMethods.filterArgs(
            ImmutableList.of("arg1", "--option1", "value1", "arg2"), ImmutableSet.of("--option1")),
        Matchers.contains("arg1", "arg2"));

    assertThat(
        BuckArgsMethods.filterArgs(
            ImmutableList.of("arg1", "--option1", "value1", "--option1", "value2"),
            ImmutableSet.of("--option1")),
        Matchers.contains("arg1"));

    assertThat(
        BuckArgsMethods.filterArgs(
            ImmutableList.of("--option1", "value1", "--option1", "value2"),
            ImmutableSet.of("--option1")),
        Matchers.empty());

    assertThat(
        BuckArgsMethods.filterArgs(
            ImmutableList.of("--option1", "value1", "--option1", "value2", "arg1", "arg2"),
            ImmutableSet.of("--option1")),
        Matchers.contains("arg1", "arg2"));
  }
}

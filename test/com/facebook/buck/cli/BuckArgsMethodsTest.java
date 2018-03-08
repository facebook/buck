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

import com.facebook.buck.rules.RelativeCellName;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.BuckArgsMethods;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuckArgsMethodsTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testArgFileExpansion() throws IOException {
    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1"),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "arg@a"),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg@a"));

    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + arg),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + arg, "arg4"),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3", "arg4"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("@" + arg),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg2", "arg3"));

    Path arg4 = tmp.newFile("argsfile4");
    Files.write(arg4, ImmutableList.of("arg4"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + arg, "@" + arg4),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3", "arg4"));

    Path argWithSpace = tmp.newFile("argsfile_space");
    Files.write(argWithSpace, ImmutableList.of("arg "));
    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("@" + argWithSpace),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg "));

    Path pyArg = tmp.newFile("argsfile.py");
    Files.write(pyArg, ImmutableList.of("print('arg2'); print('arg3');"));
    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + pyArg + "#flavor"),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + pyArg),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3"));

    Path pyArgWithFlavors = tmp.newFile("argsfilewithflavors.py");
    Files.write(
        pyArgWithFlavors, ImmutableList.of("import sys; print(sys.argv[1]); print(sys.argv[2])"));
    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + pyArgWithFlavors + "#fl1,fl2"),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "--flavors", "fl1,fl2"));
  }

  @Test
  public void flagsFromFlagFileAreExtracted() throws Exception {
    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "--flagfile", arg.toString()),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3"));
  }

  @Test
  public void passThroughFlagsAreNotProcessed() throws Exception {
    Path arg = tmp.newFile("argsfile");

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "--", "--flagfile", arg.toString()),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "--", "--flagfile", arg.toString()));
  }

  @Test
  public void flagFileFlagIsProcessedBeforePassThroughMarker() throws Exception {
    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "--flagfile", arg.toString(), "--", "--flagfile", "foo"),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3", "--", "--flagfile", "foo"));
  }

  @Test
  public void bothAtFileSyntaxAreSupported() throws Exception {
    Path argsfile = tmp.newFile("argsfile");
    Files.write(argsfile, ImmutableList.of("arg2", "arg3"));

    Path argsfile2 = tmp.newFile("argsfile2");
    Files.write(argsfile2, ImmutableList.of("arg4", "arg5"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "--flagfile", argsfile.toString(), "@argsfile2"),
            ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3", "arg4", "arg5"));
  }

  @Test
  public void invalidUsageOfFlagFileArgumentIsReported() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("--flagfile should be followed by a path.");

    BuckArgsMethods.expandAtFiles(
        ImmutableList.of("arg1", "--flagfile"),
        ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot()));
  }

  @Test
  public void testCellRelativeFlagFile() throws Exception {
    tmp.newFolder("cell");
    Path argsfile = tmp.newFile("cell/argsfile");
    Files.write(argsfile, ImmutableList.of("arg1", "arg2"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("--flagfile", "subcell//argsfile"),
            ImmutableMap.of(
                RelativeCellName.ROOT_CELL_NAME,
                tmp.getRoot(),
                RelativeCellName.fromComponents("subcell"),
                tmp.getRoot().resolve("cell"))),
        Matchers.contains("arg1", "arg2"));
  }

  @Test
  public void testNonExistentCellNameIgnores() throws Exception {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("The cell 'cell' was not found. Did you mean 'cell/argsfile'?");

    tmp.newFolder("cell");
    Path argsfile = tmp.newFile("cell/argsfile");
    Files.write(argsfile, ImmutableList.of("arg1", "arg2"));

    BuckArgsMethods.expandAtFiles(
        ImmutableList.of("--flagfile", "cell//argsfile"),
        ImmutableMap.of(RelativeCellName.ROOT_CELL_NAME, tmp.getRoot()));
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

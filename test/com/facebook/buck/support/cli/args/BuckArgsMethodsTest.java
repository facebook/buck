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

package com.facebook.buck.support.cli.args;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
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
            ImmutableList.of("arg1"), ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "arg@a"),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg@a"));

    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + arg),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + arg, "arg4"),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3", "arg4"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("@" + arg), ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg2", "arg3"));

    Path arg4 = tmp.newFile("argsfile4");
    Files.write(arg4, ImmutableList.of("arg4"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + arg, "@" + arg4),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3", "arg4"));

    Path argWithSpace = tmp.newFile("argsfile_space");
    Files.write(argWithSpace, ImmutableList.of("arg "));
    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("@" + argWithSpace),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg "));

    Path pyArg = tmp.newFile("argsfile.py");
    Files.write(pyArg, ImmutableList.of("print('arg2'); print('arg3');"));
    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + pyArg + "#flavor"),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + pyArg),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3"));

    Path pyArgWithFlavors = tmp.newFile("argsfilewithflavors.py");
    Files.write(
        pyArgWithFlavors, ImmutableList.of("import sys; print(sys.argv[1]); print(sys.argv[2])"));
    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "@" + pyArgWithFlavors + "#fl1,fl2"),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "--flavors", "fl1,fl2"));
  }

  @Test
  public void flagsFromFlagFileAreExtracted() throws Exception {
    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "--flagfile", arg.toString()),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3"));
  }

  @Test
  public void passThroughFlagsAreNotProcessed() throws Exception {
    Path arg = tmp.newFile("argsfile");

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "--", "--flagfile", arg.toString()),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "--", "--flagfile", arg.toString()));
  }

  @Test
  public void flagFileFlagIsProcessedBeforePassThroughMarker() throws Exception {
    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("arg2", "arg3"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", "--flagfile", arg.toString(), "--", "--flagfile", "foo"),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3", "--", "--flagfile", "foo"));
  }

  @Test
  public void nestedFlagFileIsSupported() throws Exception {
    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("arg1", "--flagfile", "flagfile"));
    Path nestedFlagFile = tmp.newFile("flagfile");
    Files.write(nestedFlagFile, ImmutableList.of("arg2"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg0", "--flagfile", arg.toString()),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg0", "arg1", "arg2"));
  }

  @Test
  public void nestedAtFileIsSupported() throws Exception {
    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("arg1", "@flagfile"));
    Path nestedFlagFile = tmp.newFile("flagfile");
    Files.write(nestedFlagFile, ImmutableList.of("arg2"));

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg0", "--flagfile", arg.toString()),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg0", "arg1", "arg2"));
  }

  @Test
  public void nestedFlagFileExpansionLoopDetected() throws Exception {
    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("--flagfile", "flagfile"));
    Path flagFile = tmp.newFile("flagfile");
    Files.write(flagFile, ImmutableList.of("--flagfile", "argsfile"));

    thrown.expectMessage("Expansion loop detected:");
    thrown.expectMessage("argsfile -> flagfile -> argsfile");
    BuckArgsMethods.expandAtFiles(
        ImmutableList.of("arg0", "--flagfile", arg.toString()),
        ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot()));
  }

  @Test
  public void mixOfAtAndFlagFileExpansionLoopIsDetected() throws Exception {
    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("--flagfile", "flagfile"));
    Path flagFile = tmp.newFile("flagfile");
    Files.write(flagFile, ImmutableList.of("@argsfile"));

    thrown.expectMessage("Expansion loop detected:");
    thrown.expectMessage("argsfile -> flagfile -> argsfile");
    BuckArgsMethods.expandAtFiles(
        ImmutableList.of("arg0", "--flagfile", arg.toString()),
        ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot()));
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
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3", "arg4", "arg5"));
  }

  @Test
  public void invalidUsageOfFlagFileArgumentIsReported() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("--flagfile should be followed by a path.");

    BuckArgsMethods.expandAtFiles(
        ImmutableList.of("arg1", "--flagfile"),
        ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot()));
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
                CellName.ROOT_CELL_NAME,
                tmp.getRoot(),
                CellName.of("subcell"),
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
        ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot()));
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

  @Test
  public void testStripsEmptyLines() throws IOException {
    Path staticArgs = tmp.newFile("args_static");
    Path pythonArgs = tmp.newFile("args.py");

    Files.write(staticArgs, "--foo\n\nbar \n--baz\n\n".getBytes(Charsets.UTF_8));
    Files.write(
        pythonArgs, "print(\"--py-foo\\n\\npy-bar \\n--py-baz\\n\")\n".getBytes(Charsets.UTF_8));

    ImmutableMap<CellName, Path> cellMapping =
        ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot());
    assertEquals(
        ImmutableList.of("--foo", "bar ", "--baz"),
        BuckArgsMethods.expandAtFiles(ImmutableList.of("@" + staticArgs.toString()), cellMapping));
    assertEquals(
        ImmutableList.of("--py-foo", "py-bar ", "--py-baz"),
        BuckArgsMethods.expandAtFiles(ImmutableList.of("@" + pythonArgs.toString()), cellMapping));
  }

  @Test
  public void testHandlesAtSymbolAfterTwoDashes() throws IOException {
    Path arg = tmp.newFile("argsfile");
    Files.write(arg, ImmutableList.of("arg2", "arg3"));
    String atArg = "@" + arg.toString();

    assertThat(
        BuckArgsMethods.expandAtFiles(
            ImmutableList.of("arg1", atArg, "--", atArg),
            ImmutableMap.of(CellName.ROOT_CELL_NAME, tmp.getRoot())),
        Matchers.contains("arg1", "arg2", "arg3", "--", atArg));
  }
}

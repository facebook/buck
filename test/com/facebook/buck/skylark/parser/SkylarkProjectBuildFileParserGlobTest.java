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

package com.facebook.buck.skylark.parser;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.starlark.eventhandler.EventHandler;
import com.facebook.buck.core.starlark.eventhandler.EventKind;
import com.facebook.buck.core.starlark.eventhandler.PrintingEventHandler;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

public class SkylarkProjectBuildFileParserGlobTest {
  private SkylarkProjectBuildFileParser parser;
  private ProjectFilesystem projectFilesystem;
  private KnownRuleTypesProvider knownRuleTypesProvider;

  @Rule public ExpectedException thrown = ExpectedException.none();
  private Cells cell;

  @Before
  public void setUp() {
    projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    knownRuleTypesProvider = TestKnownRuleTypesProvider.create(pluginManager);
    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));
  }

  private ProjectBuildFileParserOptions.Builder getDefaultParserOptions() {
    return SkylarkProjectBuildFileParserTestUtils.getDefaultParserOptions(
        cell.getRootCell(), knownRuleTypesProvider);
  }

  private SkylarkProjectBuildFileParser createParserWithOptions(
      EventHandler eventHandler, ProjectBuildFileParserOptions options) {
    return SkylarkProjectBuildFileParserTestUtils.createParserWithOptions(
        eventHandler, options, knownRuleTypesProvider, cell.getRootCell());
  }

  private SkylarkProjectBuildFileParser createParser(EventHandler eventHandler) {
    return createParserWithOptions(eventHandler, getDefaultParserOptions().build());
  }

  private RawTargetNode getSingleRule(AbsPath buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    return SkylarkProjectBuildFileParserTestUtils.getSingleRule(parser, buildFile);
  }

  @Test
  public void globFunction() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    AbsPath buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory.getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name='guava', binary_jar='foo.jar', licenses=glob(['f*']))"));
    Files.createFile(directory.resolve("file1").getPath());
    Files.createFile(directory.resolve("file2").getPath());
    Files.createFile(directory.resolve("bad_file").getPath());
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("licenses"), equalTo(ImmutableList.of("file1", "file2")));
  }

  @Test
  public void globResultsMatchCurrentStateIfStateIsUnchanged() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    AbsPath buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory.getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name='guava', binary_jar='foo.jar', licenses=glob(['f*']))"));
    Files.createFile(directory.resolve("file1").getPath());
    Files.createFile(directory.resolve("file2").getPath());
    Files.createFile(directory.resolve("bad_file").getPath());

    boolean result =
        parser.globResultsMatchCurrentState(
            buildFile,
            ImmutableList.of(
                GlobSpecWithResult.of(
                    GlobSpec.of(Collections.singletonList("f*"), Collections.EMPTY_LIST, false),
                    ImmutableSet.of("file2", "file1"))));

    assertTrue(result);
  }

  @Test
  public void globResultsDontMatchCurrentStateIfStateIsChanged() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    AbsPath buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory.getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name='guava', binary_jar='foo.jar', licenses=glob(['f*']))"));
    Files.createFile(directory.resolve("file1").getPath());
    Files.createFile(directory.resolve("file2").getPath());
    Files.createFile(directory.resolve("bad_file").getPath());

    boolean result =
        parser.globResultsMatchCurrentState(
            buildFile,
            ImmutableList.of(
                GlobSpecWithResult.of(
                    GlobSpec.of(Collections.singletonList("f*"), Collections.EMPTY_LIST, false),
                    ImmutableSet.of("file3", "file1"))));

    assertFalse(result);
  }

  @Test
  public void globResultsDontMatchCurrentStateIfCurrentStateHasMoreEntries() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    AbsPath buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory.getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name='guava', binary_jar='foo.jar', licenses=glob(['f*']))"));
    Files.createFile(directory.resolve("file1").getPath());
    Files.createFile(directory.resolve("file2").getPath());
    Files.createFile(directory.resolve("bad_file").getPath());

    boolean result =
        parser.globResultsMatchCurrentState(
            buildFile,
            ImmutableList.of(
                GlobSpecWithResult.of(
                    GlobSpec.of(Collections.singletonList("f*"), Collections.EMPTY_LIST, false),
                    ImmutableSet.of("file1"))));

    assertFalse(result);
  }

  @Test
  public void globResultsDontMatchCurrentStateIfCurrentStateHasLessEntries() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    AbsPath buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory.getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name='guava', binary_jar='foo.jar', licenses=glob(['f*']))"));
    Files.createFile(directory.resolve("file1").getPath());
    Files.createFile(directory.resolve("bad_file").getPath());

    boolean result =
        parser.globResultsMatchCurrentState(
            buildFile,
            ImmutableList.of(
                GlobSpecWithResult.of(
                    GlobSpec.of(Collections.singletonList("f*"), Collections.EMPTY_LIST, false),
                    ImmutableSet.of("file1", "file2"))));

    assertFalse(result);
  }

  @Test
  public void globResultsMatchCurrentStateIfCurrentStateAndResultsAreEmpty() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    AbsPath buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory.getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name='guava', binary_jar='foo.jar', licenses=glob(['f*']))"));
    Files.createFile(directory.resolve("bad_file").getPath());

    boolean result =
        parser.globResultsMatchCurrentState(
            buildFile,
            ImmutableList.of(
                GlobSpecWithResult.of(
                    GlobSpec.of(Collections.singletonList("f*"), Collections.EMPTY_LIST, false),
                    ImmutableSet.of())));

    assertTrue(result);
  }

  @Test
  public void canUseGlobalGlobFunctionInsideOfExtension() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList("load('//src/test:build_rules.bzl', 'guava_jar')", "guava_jar(name='foo')"));
    Files.write(
        extensionFile.getPath(),
        Arrays.asList(
            "def guava_jar(name):",
            "  native.prebuilt_jar(name=name, binary_jar='foo.jar', licenses=glob(['*.txt']))"));
    getSingleRule(buildFile);
  }

  @Test
  public void canUseNativeGlobFunctionInsideOfExtension() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList("load('//src/test:build_rules.bzl', 'guava_jar')", "guava_jar(name='foo')"));
    Files.write(
        extensionFile.getPath(),
        Arrays.asList(
            "def guava_jar(name):",
            "  native.prebuilt_jar(name=name, binary_jar='foo.jar', licenses=native.glob(['*.txt']))"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("name"), equalTo("foo"));
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("foo.jar"));
    assertThat(rule.getBySnakeCase("licenses"), equalTo(ImmutableList.of()));
  }

  @Test
  public void globManifestIsCapturedFunction() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    AbsPath buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory.getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name='guava', binary_jar='foo.jar', licenses=glob(['f*']))"));
    Files.createFile(directory.resolve("file1").getPath());
    Files.createFile(directory.resolve("file2").getPath());
    Files.createFile(directory.resolve("bad_file").getPath());
    BuildFileManifest buildFileManifest = parser.getManifest(buildFile);
    assertThat(buildFileManifest.getTargets(), Matchers.aMapWithSize(1));
    assertThat(
        buildFileManifest.getGlobManifest(),
        equalTo(
            ImmutableList.of(
                GlobSpecWithResult.of(
                    GlobSpec.of(ImmutableList.of("f*"), ImmutableList.of(), true),
                    ImmutableSet.of("file1", "file2")))));
  }
}

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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.targetgraph.DescriptionProvider;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.starlark.eventhandler.Event;
import com.facebook.buck.core.starlark.eventhandler.EventCollector;
import com.facebook.buck.core.starlark.eventhandler.EventHandler;
import com.facebook.buck.core.starlark.eventhandler.EventKind;
import com.facebook.buck.core.starlark.eventhandler.PrintingEventHandler;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.implicit.ImplicitInclude;
import com.facebook.buck.parser.options.ImplicitNativeRulesState;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.parser.options.UserDefinedRulesState;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.skylark.function.SkylarkBuildModule;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.skylark.io.impl.NativeGlobber;
import com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParserTestUtils.RecordingParser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.starlark.java.eval.StarlarkList;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

public class SkylarkProjectBuildFileParserTest {

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

  @Test
  public void canParsePrebuiltJarRule() throws Exception {
    AbsPath buildFile = projectFilesystem.resolve("src").resolve("test").resolve("BUCK");
    Files.createDirectories(buildFile.getParent().getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar("
                + "name='guava',"
                + "binary_jar='guava.jar',"
                + "licenses=['LICENSE'],"
                + "source_jar='guava-sources.jar',"
                + "visibility=['PUBLIC'],"
                + ")"));

    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("name"), equalTo("guava"));
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("guava.jar"));
    assertThat(rule.getBySnakeCase("licenses"), equalTo(ImmutableList.of("LICENSE")));
    assertThat(rule.getBySnakeCase("source_jar"), equalTo("guava-sources.jar"));
    assertThat(rule.getVisibility(), equalTo(ImmutableList.of("PUBLIC")));
    assertThat(rule.getBasePath().toString(), equalTo("src/test"));
  }

  @Test
  public void detectsDuplicateRuleDefinition() throws Exception {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(eventCollector);
    AbsPath buildFile = projectFilesystem.resolve("src").resolve("BUCK");
    Files.createDirectories(buildFile.getParent().getPath());
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "prebuilt_jar(name='guava', binary_jar='guava.jar')",
            "prebuilt_jar(name='guava', binary_jar='guava.jar')"));
    try {
      parser.getManifest(buildFile);
      fail();
    } catch (BuildFileParseException e) {
      assertThat(
          e.getMessage(),
          endsWith(
              "Cannot register rule src:guava of type prebuilt_jar with content {name=guava, binary_jar=guava.jar} again."));
    }
  }

  @Test
  public void detectsInvalidAttribute() throws Exception {
    AbsPath buildFile = projectFilesystem.resolve("src").resolve("test").resolve("BUCK");
    Files.createDirectories(buildFile.getParent().getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar("
                + "name='guava',"
                + "binary_jarz='guava.jar',"
                + "licenses=['LICENSE'],"
                + "source_jar='guava-sources.jar',"
                + "visibility=['PUBLIC'],"
                + ")"));

    thrown.expect(BuildFileParseException.class);

    parser.getManifest(buildFile);
  }

  @Test
  public void detectsMissingRequiredAttribute() throws Exception {
    AbsPath buildFile = projectFilesystem.resolve("src").resolve("test").resolve("BUCK");
    Files.createDirectories(buildFile.getParent().getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar("
                + "name='guava',"
                + "licenses=['LICENSE'],"
                + "source_jar='guava-sources.jar',"
                + "visibility=['PUBLIC'],"
                + ")"));

    thrown.expect(BuildFileParseException.class);

    parser.getManifest(buildFile);
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
  public void lazyRangeIsUsedFunction() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    AbsPath buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory.getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList("prebuilt_jar(name=type(range(5)), binary_jar='foo.jar')"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("name"), equalTo("range"));
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
                    GlobSpec.of(StarlarkList.of(null, "f*"), StarlarkList.of(null), true),
                    ImmutableSet.of("file1", "file2")))));
  }

  @Test
  public void readConfigFunction() throws Exception {
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name=read_config('app', 'name', 'guava'), binary_jar='foo.jar')"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("name"), equalTo("guava"));
  }

  @Test
  public void accessedUnsetConfigOptionIsRecorded() throws Exception {
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile.getPath(), ImmutableList.of("val = read_config('app', 'name', 'guava')"));
    BuildFileManifest buildFileManifest = parser.getManifest(buildFile);
    Map<String, Object> configs = buildFileManifest.getReadConfigurationOptionsForTest();
    assertEquals(ImmutableMap.of("app", ImmutableMap.of("name", Optional.empty())), configs);
  }

  @Test
  public void accessedSetConfigOptionIsRecorded() throws Exception {
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        ImmutableList.of("val = read_config('dummy_section', 'dummy_key', 'dummy_value')"));
    BuildFileManifest buildFileManifest = parser.getManifest(buildFile);
    Map<String, Object> configs = buildFileManifest.getReadConfigurationOptionsForTest();
    assertEquals(
        ImmutableMap.of("dummy_section", ImmutableMap.of("dummy_key", Optional.of("dummy_value"))),
        configs);
  }

  @Test
  public void missingRequiredAttributeIsReported() throws Exception {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(eventCollector);
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile.getPath(), Collections.singletonList("prebuilt_jar()"));
    try {
      parser.getManifest(buildFile);
      fail("Should not reach here.");
    } catch (BuildFileParseException e) {
      assertThat(e.getMessage(), startsWith("Cannot evaluate file "));
      assertThat(
          e.getMessage(),
          stringContainsInOrder(
              "prebuilt_jar requires name and binary_jar but they are not provided.",
              "Need help? See https://dev.buck.build/rule/prebuilt_jar"));
    }
  }

  @Test
  public void packageNameFunction() throws Exception {
    AbsPath buildFile = projectFilesystem.resolve("pkg").resolve("BUCK");
    Files.createDirectories(buildFile.getParent().getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList("prebuilt_jar(name=package_name(), binary_jar='foo.jar')"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("name"), equalTo("pkg"));
  }

  @Test
  public void canUsePrintInBuildFile() throws Exception {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(eventCollector);
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile.getPath(), Collections.singletonList("print('hello world')"));
    parser.getManifest(buildFile);
    Event printEvent = eventCollector.iterator().next();
    assertThat(printEvent.getMessage(), equalTo("hello world"));
    assertThat(printEvent.getKind(), equalTo(EventKind.DEBUG));
  }

  @Test
  public void canUsePrintInExtensionFile() throws Exception {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(eventCollector);
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile.getPath(), Collections.singletonList("load('//:ext.bzl', 'ext')"));
    AbsPath extensionFile = projectFilesystem.resolve("ext.bzl");
    Files.write(extensionFile.getPath(), Arrays.asList("ext = 'hello'", "print('hello world')"));
    parser.getManifest(buildFile);
    Event printEvent = eventCollector.iterator().next();
    assertThat(printEvent.getMessage(), equalTo("hello world"));
    assertThat(printEvent.getKind(), equalTo(EventKind.DEBUG));
  }

  @Test
  public void readConfigUsageAtTopLevel() throws Exception {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(eventCollector);
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile.getPath(), ImmutableList.of("load('//:ext.bzl', 'ext')", "print(ext)"));
    AbsPath extensionFile = projectFilesystem.resolve("ext.bzl");
    Files.write(
        extensionFile.getPath(),
        Collections.singletonList("ext = native.read_config('foo', 'bar', 'baz')"));
    parser.getManifest(buildFile);
    Event printEvent = eventCollector.iterator().next();
    assertThat(printEvent.getMessage(), equalTo("baz"));
  }

  @Test
  public void nativeReadConfigFunctionCanBeInvokedFromExtensionFile() throws Exception {
    AbsPath buildFileDirectory = projectFilesystem.resolve("test");
    Files.createDirectories(buildFileDirectory.getPath());
    AbsPath buildFile = buildFileDirectory.resolve("BUCK");
    AbsPath extensionFileDirectory = buildFileDirectory.resolve("ext");
    Files.createDirectories(extensionFileDirectory.getPath());
    AbsPath extensionFile = extensionFileDirectory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//test/ext:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionFile.getPath(),
        Arrays.asList("def get_name():", "  return native.read_config('foo', 'bar', 'baz')"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("baz"));
  }

  @Test
  public void canLoadSameExtensionMultipleTimes() throws Exception {
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        Arrays.asList("load('//:ext.bzl', 'ext')", "load('//:ext.bzl', ext2='ext')"));
    AbsPath extensionFile = projectFilesystem.resolve("ext.bzl");
    Files.write(extensionFile.getPath(), Arrays.asList("ext = 'hello'", "print('hello world')"));
    assertTrue(parser.getManifest(buildFile).getTargets().isEmpty());
  }

  @Test
  public void doesNotReadSameExtensionMultipleTimes() throws Exception {
    // Verifies each extension file is accessed for IO and AST construction only once.
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        Arrays.asList("load('//:ext_1.bzl', 'ext_1')", "load('//:ext_2.bzl', 'ext_2')"));

    AbsPath ext1 = projectFilesystem.resolve("ext_1.bzl");
    Files.write(ext1.getPath(), Arrays.asList("load('//:ext_2.bzl', 'ext_2')", "ext_1 = ext_2"));

    AbsPath ext2 = projectFilesystem.resolve("ext_2.bzl");
    Files.write(ext2.getPath(), Collections.singletonList("ext_2 = 'hello'"));

    RecordingParser recordingParser = new RecordingParser(parser);
    recordingParser.getManifest(buildFile);

    assertThat(
        recordingParser.readCounts,
        equalTo(recordingParser.expectedCounts(buildFile, 1, ext1, 1, ext2, 1)));
  }

  @Test
  public void doesNotBuildSameExtensionMultipleTimes() throws Exception {
    // Verifies each extension file is accessed for IO and AST construction only once.
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        Arrays.asList("load('//:ext_1.bzl', 'ext_1')", "load('//:ext_2.bzl', 'ext_2')"));

    AbsPath ext1 = projectFilesystem.resolve("ext_1.bzl");
    // Note: using relative path for load.
    Files.write(ext1.getPath(), Arrays.asList("load(':ext_2.bzl', 'ext_2')", "ext_1 = ext_2"));

    AbsPath ext2 = projectFilesystem.resolve("ext_2.bzl");
    Files.write(ext2.getPath(), Collections.singletonList("ext_2 = 'hello'"));

    RecordingParser recordingParser = new RecordingParser(parser);
    recordingParser.getManifest(buildFile);

    assertThat(
        recordingParser.buildCounts, equalTo(recordingParser.expectedCounts(ext1, 1, ext2, 1)));
  }

  @Test
  public void doesNotReadSameBuildFileMultipleTimes() throws Exception {
    // Verifies BUILD file is accessed for IO and AST construction only once.
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile.getPath(), Collections.singletonList("_var = 'hello'"));

    RecordingParser recordingParser = new RecordingParser(parser);
    recordingParser.getManifest(buildFile);
    recordingParser.getIncludedFiles(buildFile);
    assertThat(recordingParser.readCounts, equalTo(recordingParser.expectedCounts(buildFile, 1)));
  }

  @Test
  public void canHandleSameExtensionLoadedMultipleTimesFromAnotherExtension() throws Exception {
    // Verifies we can handle the case when the same extension is loaded multiple times from another
    // extension.
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile.getPath(), Collections.singletonList("load('//:ext_1.bzl', 'ext_1')"));

    AbsPath ext1 = projectFilesystem.resolve("ext_1.bzl");
    Files.write(
        ext1.getPath(),
        Arrays.asList(
            "load('//:ext_2.bzl', 'ext_2')",
            "load('//:ext_2.bzl', ext2_copy='ext_2')",
            "ext_1 = ext_2"));

    AbsPath ext2 = projectFilesystem.resolve("ext_2.bzl");
    Files.write(ext2.getPath(), Collections.singletonList("ext_2 = 'hello'"));

    parser.getManifest(buildFile);
  }

  @Test
  public void canHandleSameExtensionLoadedMultipleTimesFromBuildFile() throws Exception {
    // Verifies we can handle the case when the same extension is loaded multiple times from a BUILD
    // file.
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        Arrays.asList("load('//:ext_1.bzl', 'ext_1')", "load('//:ext_1.bzl', ext1_copy='ext_1')"));

    AbsPath ext1 = projectFilesystem.resolve("ext_1.bzl");
    Files.write(ext1.getPath(), Collections.singletonList("ext_1 = 'hello'"));

    parser.getManifest(buildFile);
  }

  @Test
  public void packageNameFunctionInExtensionUsesBuildFilePackage() throws Exception {
    AbsPath buildFileDirectory = projectFilesystem.resolve("test");
    Files.createDirectories(buildFileDirectory.getPath());
    AbsPath buildFile = buildFileDirectory.resolve("BUCK");
    AbsPath extensionFileDirectory = buildFileDirectory.resolve("ext");
    Files.createDirectories(extensionFileDirectory.getPath());
    AbsPath extensionFile = extensionFileDirectory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//test/ext:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionFile.getPath(),
        Arrays.asList("def get_name():", "  return native.package_name()"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("test"));
  }

  @Test
  public void repositoryNameFunctionInExtensionReturnsCellName() throws Exception {
    AbsPath buildFileDirectory = projectFilesystem.resolve("test");
    Files.createDirectories(buildFileDirectory.getPath());
    AbsPath buildFile = buildFileDirectory.resolve("BUCK");
    AbsPath extensionFileDirectory = buildFileDirectory.resolve("ext");
    Files.createDirectories(extensionFileDirectory.getPath());
    AbsPath extensionFile = extensionFileDirectory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//test/ext:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionFile.getPath(),
        Arrays.asList("def get_name():", "  return native.repository_name()"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("@"));
  }

  @Test
  public void repositoryNameFunctionInBuildFileReturnsCellName() throws Exception {
    AbsPath buildFileDirectory = projectFilesystem.resolve("test");
    Files.createDirectories(buildFileDirectory.getPath());
    AbsPath buildFile = buildFileDirectory.resolve("BUCK");
    AbsPath extensionFileDirectory = buildFileDirectory.resolve("ext");
    Files.createDirectories(extensionFileDirectory.getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList("prebuilt_jar(name='foo', binary_jar=repository_name())"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("@"));
  }

  @Test
  public void testImportVariable() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'JAR')",
            "prebuilt_jar(name='foo', binary_jar=JAR)"));
    Files.write(extensionFile.getPath(), Collections.singletonList("JAR='jar'"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("jar"));
  }

  @Test
  public void canUseStructsInExtensionFiles() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'jar')",
            "prebuilt_jar(name='foo', binary_jar=jar)"));
    Files.write(extensionFile.getPath(), Arrays.asList("s = struct(x='j',y='ar')", "jar=s.x+s.y"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("jar"));
  }

  @Test
  public void canUseProvidersInExtensionFiles() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'jar')",
            "prebuilt_jar(name='foo', binary_jar=jar)"));
    Files.write(
        extensionFile.getPath(),
        Arrays.asList("Info = provider(fields=['data'])", "s = Info(data='data')", "jar=s.data"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("data"));
  }

  @Test
  public void canUsePerLanguageProvidersInExtensionFiles()
      throws IOException, InterruptedException {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name=get_name('foo'), binary_jar='')"));
    Files.write(
        extensionFile.getPath(),
        Arrays.asList(
            "def get_name(x):",
            "    if \"DotnetLibraryProviderInfo\" not in str(DotnetLibraryProviderInfo):",
            "        fail(\"Name was not as expected\")",
            "    return x + \"_custom\""));

    ProjectBuildFileParserOptions options =
        getDefaultParserOptions().setUserDefinedRulesState(UserDefinedRulesState.ENABLED).build();
    knownRuleTypesProvider.getNativeRuleTypes(cell.getRootCell()).getDescriptions();
    System.err.printf(
        "Got %s descriptions\n%s providers\n%s from PM\n%n",
        knownRuleTypesProvider.getNativeRuleTypes(cell.getRootCell()).getDescriptions(),
        knownRuleTypesProvider.getNativeRuleTypes(cell.getRootCell()).getPerFeatureProviders(),
        BuckPluginManagerFactory.createPluginManager().getExtensions(DescriptionProvider.class));
    parser = createParserWithOptions(parser.eventHandler, options);
    SkylarkProjectBuildFileParserTestUtils.getSingleRule(parser, buildFile);
    RawTargetNode rule = getSingleRule(buildFile);

    assertThat(rule.getBySnakeCase("name"), equalTo("foo_custom"));
  }

  @Test
  public void testImportFunction() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(extensionFile.getPath(), Arrays.asList("def get_name():", "  return 'jar'"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("jar"));
  }

  @Test
  public void testImportFunctionUsingBuiltInRule() throws Exception {
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
            "def guava_jar(name):", "  native.prebuilt_jar(name=name, binary_jar='foo.jar')"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("name"), equalTo("foo"));
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("foo.jar"));
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
  public void canUseBuiltInListFunction() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name='a', binary_jar='a.jar', licenses=list(('l1', 'l2')))"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("licenses"), equalTo(ImmutableList.of("l1", "l2")));
  }

  @Test
  public void canUseUnicodeChars() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        Collections.singletonList("prebuilt_jar(name='β', binary_jar='a.jar')"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("name"), equalTo("β"));
  }

  @Test
  public void functionDefinitionsAreNotAllowedInBuildFiles() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    Files.write(buildFile.getPath(), Arrays.asList("def foo():", "  pass"));

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot parse");

    parser.getManifest(buildFile);
  }

  @Test
  public void evaluationErrorIsReported() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    Files.write(buildFile.getPath(), Collections.singletonList("foo()"));

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        Matchers.anyOf(
            containsString("Cannot parse " + buildFile.toString().replace('/', '\\')),
            containsString("Cannot parse " + buildFile.toString().replace('\\', '/'))));

    parser.getManifest(buildFile);
  }

  @Test
  public void extensioSkylarkPackageFileParserTestnFileEvaluationErrorIsReported()
      throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Collections.singletonList("load('//src/test:build_rules.bzl', 'guava_jar')"));
    Files.write(extensionFile.getPath(), Collections.singletonList("error"));

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(matchesPattern("Cannot (parse|evaluate).*build_rules.bzl.*"));

    parser.getManifest(buildFile);
  }

  @Test
  public void canUseBuiltInListFunctionInExtension() throws Exception {
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
            "  native.prebuilt_jar(name=name, binary_jar='foo.jar', licenses=list(('l1', 'l2')))"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("licenses"), equalTo(ImmutableList.of("l1", "l2")));
  }

  @Test
  public void testImportFunctionFromExtension() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    AbsPath extensionExtensionFile = directory.resolve("extension_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionExtensionFile.getPath(), Arrays.asList("def get_name():", "  return 'jar'"));
    Files.write(
        extensionFile.getPath(),
        ImmutableList.of(
            "load('//src/test:extension_rules.bzl', _get_name='get_name')",
            "get_name = _get_name"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("jar"));
  }

  @Test
  public void testCanLoadExtensionFromBuildFileUsingRelativeLabel() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load(':build_rules.bzl', 'jar')", "prebuilt_jar(name='foo', binary_jar=jar)"));
    Files.write(extensionFile.getPath(), Collections.singletonList("jar = 'jar.jar'"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("jar.jar"));
  }

  @Test
  public void testCannotLoadExtensionFromANestedDirectory() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load(':foo/build_rules.bzl', 'jar')", "prebuilt_jar(name='foo', binary_jar=jar)"));
    Files.write(extensionFile.getPath(), Collections.singletonList("jar = 'jar.jar'"));
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Relative loads work only for files in the same directory but "
            + ":foo/build_rules.bzl is trying to load a file from a nested directory. "
            + "Please use absolute label instead ([cell]//pkg[/pkg]:target).");
    getSingleRule(buildFile);
  }

  @Test
  public void testCanLoadExtensionFromExtensionUsingRelativeLabel() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    AbsPath extensionExtensionFile = directory.resolve("extension_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionExtensionFile.getPath(), Arrays.asList("def get_name():", "  return 'jar'"));
    Files.write(
        extensionFile.getPath(),
        ImmutableList.of(
            "load(':extension_rules.bzl', _get_name='get_name')", "get_name = _get_name"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("jar"));
  }

  @Test
  public void parsingOfExtensionWithSyntacticErrorsFails() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionFile.getPath(), Collections.singletonList("def get_name():\n  return 'jar'\nj j"));
    thrown.expectMessage(containsString("Cannot parse"));
    parser.getManifest(buildFile);
  }

  @Test
  public void canImportExtensionFromAnotherCell() throws Exception {
    Cells cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();

    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath anotherCell = projectFilesystem.resolve("tp2");
    AbsPath extensionDirectory = anotherCell.resolve("ext");
    Files.createDirectories(extensionDirectory.getPath());
    AbsPath extensionFile = extensionDirectory.resolve("build_rules.bzl");

    ProjectBuildFileParserOptions options =
        ProjectBuildFileParserOptions.builder()
            .setProjectRoot(cell.getRootCell().getRoot())
            .setAllowEmptyGlobs(ParserConfig.DEFAULT_ALLOW_EMPTY_GLOBS)
            .setIgnorePaths(ImmutableSet.of())
            .setBuildFileName("BUCK")
            .setDescriptions(
                knownRuleTypesProvider.getNativeRuleTypes(cell.getRootCell()).getDescriptions())
            .setPerFeatureProviders(
                knownRuleTypesProvider
                    .getNativeRuleTypes(cell.getRootCell())
                    .getPerFeatureProviders())
            .setBuildFileImportWhitelist(ImmutableList.of())
            .setPythonInterpreter("skylark")
            .setCellRoots(ImmutableMap.of("tp2", anotherCell))
            .build();
    parser =
        SkylarkProjectBuildFileParser.using(
            options,
            BuckEventBusForTests.newInstance(),
            BuckGlobals.of(
                SkylarkBuildModule.BUILD_MODULE,
                options.getDescriptions(),
                options.getUserDefinedRulesState(),
                options.getImplicitNativeRulesState(),
                new RuleFunctionFactory(new DefaultTypeCoercerFactory()),
                knownRuleTypesProvider.getUserDefinedRuleTypes(cell.getRootCell()),
                options.getPerFeatureProviders()),
            new PrintingEventHandler(EnumSet.allOf(EventKind.class)),
            NativeGlobber.Factory.INSTANCE);

    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('@tp2//ext:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionFile.getPath(), Collections.singletonList("def get_name():\n  return 'jar'"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("name"), equalTo("foo"));
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("jar"));
  }

  @Test
  public void attemptToLoadInvalidCellIsReported() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath anotherCell = projectFilesystem.resolve("tp2");
    AbsPath extensionDirectory = anotherCell.resolve("ext");
    Files.createDirectories(extensionDirectory.getPath());
    AbsPath extensionFile = extensionDirectory.resolve("build_rules.bzl");

    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('@invalid_repo//ext:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionFile.getPath(), Collections.singletonList("def get_name():\n  return 'jar'"));
    thrown.expectMessage(
        "@invalid_repo//ext:build_rules.bzl references an unknown repository invalid_repo");
    getSingleRule(buildFile);
  }

  @Test
  public void parseIncludesIsReturned() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(extensionFile.getPath(), Arrays.asList("def get_name():", "  return 'jar'"));
    ImmutableSortedSet<String> includes = parser.getIncludedFiles(buildFile);
    assertThat(includes, Matchers.hasSize(2));
    assertThat(
        includes.stream()
            .map(projectFilesystem::resolve)
            .map(
                p ->
                    p.getPath()
                        .getFileName()
                        .toString()) // simplify matching by stripping temporary path prefixes
            .collect(ImmutableList.toImmutableList()),
        Matchers.containsInAnyOrder("BUCK", "build_rules.bzl"));
  }

  @Test
  public void parseMetadataIsReturned() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(extensionFile.getPath(), Arrays.asList("def get_name():", "  return 'jar'"));
    BuildFileManifest buildFileManifest = parser.getManifest(buildFile);
    assertThat(buildFileManifest.getTargets(), Matchers.aMapWithSize(1));
    RawTargetNode prebuiltJarRule =
        Iterables.getOnlyElement(buildFileManifest.getTargets().values());
    assertThat(prebuiltJarRule.getBySnakeCase("name"), equalTo("foo"));
    ImmutableSortedSet<String> includes = buildFileManifest.getIncludes();
    assertThat(
        includes.stream()
            .map(projectFilesystem::resolve)
            .map(
                p ->
                    p.getPath()
                        .getFileName()
                        .toString()) // simplify matching by stripping temporary path prefixes
            .collect(ImmutableList.toImmutableList()),
        Matchers.containsInAnyOrder("BUCK", "build_rules.bzl"));
    Map<String, Object> configs = buildFileManifest.getReadConfigurationOptionsForTest();
    assertThat(configs, equalTo(ImmutableMap.of()));
    Optional<ImmutableMap<String, Optional<String>>> env = buildFileManifest.getEnv();
    assertFalse(env.isPresent());
  }

  @Test
  public void cannotUseNativeRulesImplicitlyInBuildFilesIfDisabled()
      throws IOException, InterruptedException {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        Collections.singletonList("prebuilt_jar(name='foo', binary_jar='guava.jar')"));

    ProjectBuildFileParserOptions options =
        getDefaultParserOptions()
            .setImplicitNativeRulesState(ImplicitNativeRulesState.of(false))
            .build();

    parser = createParserWithOptions(eventCollector, options);
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot parse");

    try {
      parser.getManifest(buildFile);
    } catch (BuildFileParseException e) {
      Event event = eventCollector.iterator().next();
      assertEquals(EventKind.ERROR, event.getKind());
      assertThat(event.getMessage(), containsString("name 'prebuilt_jar' is not defined"));
      throw e;
    }
  }

  @Test
  public void cannotUseNativeRulesImplicitlyInExtensionFilesIfDisabled()
      throws IOException, InterruptedException {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("extension.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:extension.bzl', 'make_jar')",
            "make_jar(name='foo', binary_jar='guava.jar')"));
    Files.write(
        extensionFile.getPath(),
        Arrays.asList("def make_jar(*args, **kwargs):", "    prebuilt_jar(*args, **kwargs)"));

    ProjectBuildFileParserOptions options =
        getDefaultParserOptions()
            .setImplicitNativeRulesState(ImplicitNativeRulesState.of(false))
            .build();

    parser = createParserWithOptions(eventCollector, options);
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot parse");

    try {
      parser.getManifest(buildFile);
    } catch (BuildFileParseException e) {
      Event event = eventCollector.iterator().next();
      assertEquals(EventKind.ERROR, event.getKind());
      assertThat(event.getMessage(), containsString("name 'prebuilt_jar' is not defined"));
      assertThat(event.getLocation().toString(), containsString("extension.bzl:2"));
      throw e;
    }
  }

  @Test
  public void canUseNativeRulesViaNativeModuleInExtensionFilesIfDisabled()
      throws IOException, InterruptedException {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("extension.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:extension.bzl', 'make_jar')",
            "make_jar(name='foo', binary_jar='guava.jar')"));
    Files.write(
        extensionFile.getPath(),
        Arrays.asList(
            "def make_jar(*args, **kwargs):", "    native.prebuilt_jar(*args, **kwargs)"));

    ProjectBuildFileParserOptions options =
        getDefaultParserOptions()
            .setImplicitNativeRulesState(ImplicitNativeRulesState.of(false))
            .build();

    parser = createParserWithOptions(new PrintingEventHandler(EventKind.ALL_EVENTS), options);

    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("name"), equalTo("foo"));
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("guava.jar"));
  }

  @Test
  public void canUseNativeRulesImplicitlyInBuildFilesIfNotDisabled()
      throws IOException, InterruptedException {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        Collections.singletonList("prebuilt_jar(name='foo', binary_jar='guava.jar')"));

    ProjectBuildFileParserOptions options =
        getDefaultParserOptions()
            .setImplicitNativeRulesState(ImplicitNativeRulesState.of(true))
            .build();

    parser = createParserWithOptions(new PrintingEventHandler(EventKind.ALL_EVENTS), options);

    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("name"), equalTo("foo"));
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("guava.jar"));
  }

  @Test
  public void cannotUseNativeRulesImplicitlyInExtensionFilesIfNotDisabled()
      throws IOException, InterruptedException {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("extension.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:extension.bzl', 'make_jar')",
            "make_jar(name='foo', binary_jar='guava.jar')"));
    Files.write(
        extensionFile.getPath(),
        Arrays.asList("def make_jar(*args, **kwargs):", "    prebuilt_jar(*args, **kwargs)"));

    ProjectBuildFileParserOptions options =
        getDefaultParserOptions()
            .setImplicitNativeRulesState(ImplicitNativeRulesState.of(true))
            .build();

    parser = createParserWithOptions(eventCollector, options);
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot parse");

    try {
      parser.getManifest(buildFile);
    } catch (BuildFileParseException e) {
      Event event = eventCollector.iterator().next();
      assertEquals(EventKind.ERROR, event.getKind());
      assertThat(event.getMessage(), containsString("name 'prebuilt_jar' is not defined"));
      assertThat(event.getLocation().toString(), containsString("extension.bzl:2"));
      throw e;
    }
  }

  @Test
  public void ruleDoesNotExistIfNotDefined() throws Exception {
    AbsPath buildFile = projectFilesystem.resolve("pkg").resolve("BUCK");
    Files.createDirectories(buildFile.getParent().getPath());
    Files.write(
        buildFile.getPath(),
        Collections.singletonList("prebuilt_jar(name=str(rule_exists('r')), binary_jar='foo')"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("name"), equalTo("False"));
  }

  @Test
  public void ruleDoesNotExistIfNotDefinedWhenUsedFromExtensionFile() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    AbsPath extensionExtensionFile = directory.resolve("extension_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionExtensionFile.getPath(),
        Arrays.asList("def get_name():", "  return str(native.rule_exists('does_not_exist'))"));
    Files.write(
        extensionFile.getPath(),
        ImmutableList.of(
            "load('//src/test:extension_rules.bzl', _get_name='get_name')",
            "get_name = _get_name"));
    RawTargetNode rule = getSingleRule(buildFile);
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("False"));
  }

  @Test
  public void ruleExistsIfDefined() throws Exception {
    AbsPath buildFile = projectFilesystem.resolve("pkg").resolve("BUCK");
    Files.createDirectories(buildFile.getParent().getPath());
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "prebuilt_jar(name='foo', binary_jar='binary.jar')",
            "prebuilt_jar(name=str(rule_exists('foo')), binary_jar='foo')"));
    BuildFileManifest buildFileManifest = parser.getManifest(buildFile);
    assertThat(buildFileManifest.getTargets(), Matchers.aMapWithSize(2));
    assertThat(buildFileManifest.getTargets(), hasKey("foo"));
  }

  @Test
  public void ruleExistsIfDefinedWhenUsedFromExtensionFile() throws Exception {
    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    AbsPath extensionFile = directory.resolve("build_rules.bzl");
    AbsPath extensionExtensionFile = directory.resolve("extension_rules.bzl");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='exists', binary_jar='binary.jar')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionExtensionFile.getPath(),
        Arrays.asList("def get_name():", "  return str(native.rule_exists('exists'))"));
    Files.write(
        extensionFile.getPath(),
        ImmutableList.of(
            "load('//src/test:extension_rules.bzl', _get_name='get_name')",
            "get_name = _get_name"));
    BuildFileManifest buildFileManifest = parser.getManifest(buildFile);
    assertThat(buildFileManifest.getTargets(), Matchers.aMapWithSize(2));
    RawTargetNode rule = Iterables.getFirst(buildFileManifest.getTargets().values(), null);
    assertThat(rule.getBySnakeCase("name"), is("foo"));
    assertThat(rule.getBySnakeCase("binary_jar"), equalTo("True"));
  }

  @Test
  public void throwsHumanReadableExceptionWhenFileDoesNotExist()
      throws IOException, InterruptedException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        matchesPattern(
            ".*src[\\\\/]test[\\\\/]build_rules.bzl cannot be loaded because it does not exist"));

    AbsPath directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory.getPath());
    AbsPath buildFile = directory.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='exists', binary_jar='binary.jar')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    parser.getManifest(buildFile);
  }

  @Test
  public void exportsPerPackageImplicitIncludes() throws IOException, InterruptedException {
    AbsPath rootImplicitExtension = projectFilesystem.resolve("get_name.bzl");
    AbsPath implicitExtension =
        projectFilesystem.resolve("src").resolve("foo").resolve("get_name.bzl");
    AbsPath implicitUsingExtension = projectFilesystem.resolve("get_bin_name.bzl");
    AbsPath implicitExtensionWithAliases =
        projectFilesystem.resolve("src").resolve("alias").resolve("get_name.bzl");

    Files.write(
        rootImplicitExtension.getPath(),
        Arrays.asList("def get_rule_name():", "    return \"root\""));
    Files.write(
        implicitUsingExtension.getPath(),
        Arrays.asList(
            "def get_bin_name():",
            "    return native.implicit_package_symbol('get_rule_name')() + '.jar'"));
    Files.createDirectories(implicitExtension.getParent().getPath());
    Files.write(
        implicitExtension.getPath(),
        Arrays.asList(
            "def get_rule_name():", "    return native.package_name().replace('/', '_')"));
    Files.createDirectories(implicitExtensionWithAliases.getParent().getPath());
    Files.write(
        implicitExtensionWithAliases.getPath(),
        Arrays.asList("def get_rule_name_alias():", "    return 'alias_that_symbol'"));

    parser =
        createParserWithOptions(
            new PrintingEventHandler(EventKind.ALL_EVENTS),
            getDefaultParserOptions()
                .setPackageImplicitIncludes(
                    ImmutableMap.of(
                        "",
                        ImplicitInclude.fromConfigurationString("//:get_name.bzl::get_rule_name"),
                        "src/foo",
                        ImplicitInclude.fromConfigurationString(
                            "//src/foo:get_name.bzl::get_rule_name"),
                        "src/alias",
                        ImplicitInclude.of(
                            "//src/alias:get_name.bzl",
                            ImmutableMap.of("get_rule_name", "get_rule_name_alias"))))
                .build());

    ImmutableMap<Path, String> expected =
        ImmutableMap.of(
            Paths.get("BUCK"),
            "root",
            Paths.get("src", "BUCK"),
            "root",
            Paths.get("src", "foo", "BUCK"),
            "src_foo",
            Paths.get("src", "foo", "bar", "BUCK"),
            "src_foo_bar",
            Paths.get("src", "alias", "BUCK"),
            "alias_that_symbol");

    ImmutableMap<Path, ImmutableList<Path>> expectedIncludesPaths =
        ImmutableMap.of(
            Paths.get("BUCK"),
            ImmutableList.of(
                Paths.get("BUCK"), Paths.get("get_bin_name.bzl"), Paths.get("get_name.bzl")),
            Paths.get("src", "BUCK"),
            ImmutableList.of(
                Paths.get("src", "BUCK"), Paths.get("get_bin_name.bzl"), Paths.get("get_name.bzl")),
            Paths.get("src", "foo", "BUCK"),
            ImmutableList.of(
                Paths.get("src", "foo", "BUCK"),
                Paths.get("get_bin_name.bzl"),
                Paths.get("src", "foo", "get_name.bzl")),
            Paths.get("src", "foo", "bar", "BUCK"),
            ImmutableList.of(
                Paths.get("src", "foo", "bar", "BUCK"),
                Paths.get("get_bin_name.bzl"),
                Paths.get("src", "foo", "get_name.bzl")),
            Paths.get("src", "alias", "BUCK"),
            ImmutableList.of(
                Paths.get("src", "alias", "BUCK"),
                Paths.get("get_bin_name.bzl"),
                Paths.get("src", "alias", "get_name.bzl")));

    for (Map.Entry<Path, String> kvp : expected.entrySet()) {
      Path buildFile = projectFilesystem.resolve(kvp.getKey());
      String expectedName = kvp.getValue();
      ImmutableList expectedIncludes =
          expectedIncludesPaths.get(kvp.getKey()).stream()
              .map(projectFilesystem::resolve)
              .collect(ImmutableList.toImmutableList());

      Files.createDirectories(buildFile.getParent());
      Files.write(
          buildFile,
          Arrays.asList(
              "load('//:get_bin_name.bzl', 'get_bin_name')",
              "prebuilt_jar(name=implicit_package_symbol('get_rule_name')(), binary_jar=get_bin_name())"));

      BuildFileManifest buildFileManifest = parser.getManifest(AbsPath.of(buildFile));
      assertThat(buildFileManifest.getTargets(), Matchers.aMapWithSize(1));
      RawTargetNode results = Iterables.getOnlyElement(buildFileManifest.getTargets().values());

      assertThat(
          String.format(
              "Expected file at %s to parse and have name %s", kvp.getKey(), expectedName),
          results.getBySnakeCase("name"),
          equalTo(expectedName));
      assertThat(
          String.format(
              "Expected file at %s to parse and have name %s", kvp.getKey(), expectedName),
          results.getBySnakeCase("binary_jar"),
          equalTo(expectedName + ".jar"));

      assertThat(
          String.format(
              "Expected file at %s to parse and have manifest includes %s",
              kvp.getKey(), buildFileManifest.getIncludes()),
          buildFileManifest.getIncludes().stream()
              .map(Paths::get)
              .collect(ImmutableList.toImmutableList()),
          Matchers.containsInAnyOrder(expectedIncludes.toArray()));
      assertThat(
          String.format(
              "Expected file at %s to parse and have includes %s", kvp.getKey(), expectedIncludes),
          parser.getIncludedFiles(AbsPath.of(projectFilesystem.resolve(kvp.getKey()))).stream()
              .map(Paths::get)
              .collect(ImmutableList.toImmutableList()),
          Matchers.containsInAnyOrder(expectedIncludes.toArray()));
    }
  }

  @Test
  public void returnsDefaultImplicitValueIfMissing() throws IOException, InterruptedException {
    AbsPath extension = projectFilesystem.resolve("get_name.bzl");
    AbsPath implicitExtension = projectFilesystem.resolve("src").resolve("name.bzl");
    Files.createDirectories(implicitExtension.getParent().getPath());
    Files.write(
        extension.getPath(),
        Arrays.asList(
            "def get_name():", "    return native.implicit_package_symbol('NAME', 'root')"));
    Files.write(implicitExtension.getPath(), Collections.singletonList("NAME = 'src'"));

    parser =
        createParserWithOptions(
            new PrintingEventHandler(EventKind.ALL_EVENTS),
            getDefaultParserOptions()
                .setPackageImplicitIncludes(
                    ImmutableMap.of(
                        "src", ImplicitInclude.fromConfigurationString("//src:name.bzl::NAME")))
                .build());

    AbsPath srcBuildFile = AbsPath.of(projectFilesystem.resolve(Paths.get("src", "BUCK")));
    AbsPath rootBuildFile = AbsPath.of(projectFilesystem.resolve(Paths.get("BUCK")));

    List<String> buildFileContent =
        Arrays.asList(
            "load(\"//:get_name.bzl\", \"get_name\")",
            "prebuilt_jar(",
            "    name = get_name(),",
            "    binary_jar=implicit_package_symbol('NAME', 'root') + \".jar\")");

    Files.write(srcBuildFile.getPath(), buildFileContent);
    Files.write(rootBuildFile.getPath(), buildFileContent);

    RawTargetNode srcRule = getSingleRule(srcBuildFile);
    RawTargetNode rootRule = getSingleRule(rootBuildFile);

    assertThat(srcRule.getBySnakeCase("name"), equalTo("src"));
    assertThat(srcRule.getBySnakeCase("binary_jar"), equalTo("src.jar"));
    assertThat(rootRule.getBySnakeCase("name"), equalTo("root"));
    assertThat(rootRule.getBySnakeCase("binary_jar"), equalTo("root.jar"));
  }

  @Test
  public void failsIfInvalidImplicitSymbolSpecified() throws IOException, InterruptedException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Could not find symbol 'invalid_symbol' in implicitly loaded extension '//src:get_name.bzl");

    AbsPath implicitExtension = projectFilesystem.resolve("src").resolve("get_name.bzl");
    Files.createDirectories(implicitExtension.getParent().getPath());
    Files.write(
        implicitExtension.getPath(),
        Arrays.asList(
            "def get_rule_name():", "    return native.package_name().replace('/', '_')"));

    parser =
        createParserWithOptions(
            new PrintingEventHandler(EventKind.ALL_EVENTS),
            getDefaultParserOptions()
                .setPackageImplicitIncludes(
                    ImmutableMap.of(
                        "src",
                        ImplicitInclude.fromConfigurationString(
                            "//src:get_name.bzl::invalid_symbol")))
                .build());

    AbsPath buildFile = AbsPath.of(projectFilesystem.resolve(Paths.get("src", "BUCK")));

    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name=implicit_package_symbol('get_rule_name'), binary_jar=\"foo.jar\")"));

    getSingleRule(buildFile);
  }

  @Test
  public void failsIfMissingExtensionSpecified() throws IOException, InterruptedException {
    thrown.expect(IOException.class);

    parser =
        createParserWithOptions(
            new PrintingEventHandler(EventKind.ALL_EVENTS),
            getDefaultParserOptions()
                .setPackageImplicitIncludes(
                    ImmutableMap.of(
                        "src",
                        ImplicitInclude.fromConfigurationString("//src:get_name.bzl::symbol")))
                .build());

    AbsPath buildFile = AbsPath.of(projectFilesystem.resolve(Paths.get("src", "BUCK")));

    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name=implicit_package_symbol('get_rule_name'), binary_jar=\"foo.jar\")"));

    getSingleRule(buildFile);
  }

  @Test
  public void failsIfInvalidImplicitExtensionSpecified() throws IOException, InterruptedException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot parse");

    AbsPath implicitExtension = projectFilesystem.resolve("src").resolve("get_name.bzl");
    Files.createDirectories(implicitExtension.getParent().getPath());
    Files.write(
        implicitExtension.getPath(), Collections.singletonList("def some_invalid_syntax():"));

    parser =
        createParserWithOptions(
            new PrintingEventHandler(EventKind.ALL_EVENTS),
            getDefaultParserOptions()
                .setPackageImplicitIncludes(
                    ImmutableMap.of(
                        "src",
                        ImplicitInclude.fromConfigurationString(
                            "//src:get_name.bzl::invalid_symbol")))
                .build());

    AbsPath buildFile = AbsPath.of(projectFilesystem.resolve(Paths.get("src", "BUCK")));

    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name=implicit_package_symbol('get_rule_name'), binary_jar=\"foo.jar\")"));

    getSingleRule(buildFile);
  }

  @Test
  public void failsIfMissingSymbolRequested() throws IOException, InterruptedException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot evaluate file");

    AbsPath implicitExtension = projectFilesystem.resolve("src").resolve("get_name.bzl");
    Files.createDirectories(implicitExtension.getParent().getPath());
    Files.write(
        implicitExtension.getPath(), Arrays.asList("def get_rule_name():", "    return \"foo\""));

    parser =
        createParserWithOptions(
            new PrintingEventHandler(EventKind.ALL_EVENTS),
            getDefaultParserOptions()
                .setPackageImplicitIncludes(
                    ImmutableMap.of(
                        "src",
                        ImplicitInclude.fromConfigurationString(
                            "//src:get_name.bzl::get_rule_name")))
                .build());

    AbsPath buildFile = AbsPath.of(projectFilesystem.resolve(Paths.get("src", "BUCK")));

    Files.write(
        buildFile.getPath(),
        Collections.singletonList(
            "prebuilt_jar(name=implicit_package_symbol('missing_method') + '_cant_concat_with_none', binary_jar=\"foo.jar\")"));

    getSingleRule(buildFile);
  }

  @Test
  public void cannotParsePackageRule() throws IOException, InterruptedException {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(eventCollector);
    AbsPath buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile.getPath(), Collections.singletonList("package()"));
    try {
      parser.getManifest(buildFile);
      fail("Should not reach here.");
    } catch (BuildFileParseException e) {
      assertThat(e.getMessage(), startsWith("Cannot parse "));
    }
    assertThat(eventCollector.count(), is(1));
    assertThat(
        eventCollector.iterator().next().getMessage(),
        stringContainsInOrder("name 'package' is not defined"));
  }

  private RawTargetNode getSingleRule(AbsPath buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    return SkylarkProjectBuildFileParserTestUtils.getSingleRule(parser, buildFile);
  }

  @Test
  public void parseErrorOnIncorrectImportLabel() throws Exception {
    AbsPath buildFile = AbsPath.of(projectFilesystem.resolve(Paths.get("BUCK")));

    Files.write(buildFile.getPath(), Collections.singletonList("load('bad///label', 'x')\n"));

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("BUCK:1:6:");

    getSingleRule(buildFile);
  }
}

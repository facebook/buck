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

package com.facebook.buck.skylark.parser;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.rules.knowntypes.DefaultKnownBuildRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.sandbox.TestSandboxExecutionStrategyFactory;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.impl.NativeGlobber;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.syntax.Type;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SkylarkProjectBuildFileParserTest {

  private SkylarkProjectBuildFileParser parser;
  private ProjectFilesystem projectFilesystem;
  private KnownBuildRuleTypesProvider knownBuildRuleTypesProvider;

  @Rule public ExpectedException thrown = ExpectedException.none();
  private Cell cell;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
    knownBuildRuleTypesProvider =
        KnownBuildRuleTypesProvider.of(
            DefaultKnownBuildRuleTypesFactory.of(
                new DefaultProcessExecutor(new TestConsole()),
                BuckPluginManagerFactory.createPluginManager(),
                new TestSandboxExecutionStrategyFactory()));
    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));
  }

  private ProjectBuildFileParserOptions.Builder getDefaultParserOptions() {
    return ProjectBuildFileParserOptions.builder()
        .setProjectRoot(cell.getRoot())
        .setAllowEmptyGlobs(ParserConfig.DEFAULT_ALLOW_EMPTY_GLOBS)
        .setIgnorePaths(ImmutableSet.of())
        .setBuildFileName("BUCK")
        .setRawConfig(ImmutableMap.of("dummy_section", ImmutableMap.of("dummy_key", "dummy_value")))
        .setDescriptions(knownBuildRuleTypesProvider.get(cell).getDescriptions())
        .setBuildFileImportWhitelist(ImmutableList.of())
        .setPythonInterpreter("skylark");
  }

  private SkylarkProjectBuildFileParser createParserWithOptions(
      EventHandler eventHandler, ProjectBuildFileParserOptions options) {
    return SkylarkProjectBuildFileParser.using(
        options,
        BuckEventBusForTests.newInstance(),
        SkylarkFilesystem.using(projectFilesystem),
        BuckGlobals.builder()
            .setRuleFunctionFactory(new RuleFunctionFactory(new DefaultTypeCoercerFactory()))
            .setDescriptions(options.getDescriptions())
            .setDisableImplicitNativeRules(options.getDisableImplicitNativeRules())
            .build(),
        eventHandler,
        NativeGlobber::create);
  }

  private SkylarkProjectBuildFileParser createParser(EventHandler eventHandler) {
    return createParserWithOptions(eventHandler, getDefaultParserOptions().build());
  }

  @Test
  public void canParsePrebuiltJarRule() throws Exception {
    Path buildFile = projectFilesystem.resolve("src").resolve("test").resolve("BUCK");
    Files.createDirectories(buildFile.getParent());
    Files.write(
        buildFile,
        Arrays.asList(
            "prebuilt_jar("
                + "name='guava',"
                + "binary_jar='guava.jar',"
                + "licenses=['LICENSE'],"
                + "source_jar='guava-sources.jar',"
                + "visibility=['PUBLIC'],"
                + ")"));

    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("name"), equalTo("guava"));
    assertThat(rule.get("binaryJar"), equalTo("guava.jar"));
    assertThat(
        Type.STRING_LIST.convert(rule.get("licenses"), "license"),
        equalTo(ImmutableList.of("LICENSE")));
    assertThat(rule.get("sourceJar"), equalTo("guava-sources.jar"));
    assertThat(
        Type.STRING_LIST.convert(rule.get("visibility"), "PUBLIC"),
        equalTo(ImmutableList.of("PUBLIC")));
    assertThat(rule.get("buck.base_path"), equalTo("src/test"));
  }

  @Test
  public void detectsDuplicateRuleDefinition() throws Exception {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(eventCollector);
    Path buildFile = projectFilesystem.resolve("src").resolve("BUCK");
    Files.createDirectories(buildFile.getParent());
    Files.write(
        buildFile,
        Arrays.asList(
            "prebuilt_jar(name='guava', binary_jar='guava.jar')",
            "prebuilt_jar(name='guava', binary_jar='guava.jar')"));
    try {
      parser.getBuildFileManifest(buildFile, new AtomicLong());
      fail();
    } catch (BuildFileParseException e) {
      Event event = Iterables.getOnlyElement(eventCollector);
      assertThat(event.getKind(), is(EventKind.ERROR));
      assertThat(
          event.getMessage(),
          is(
              "Cannot register rule guava with content {buck.base_path=src, buck.type=prebuilt_jar, name=guava, binaryJar=guava.jar} again."));
    }
  }

  @Test
  public void detectsInvalidAttribute() throws Exception {
    Path buildFile = projectFilesystem.resolve("src").resolve("test").resolve("BUCK");
    Files.createDirectories(buildFile.getParent());
    Files.write(
        buildFile,
        Arrays.asList(
            "prebuilt_jar("
                + "name='guava',"
                + "binary_jarz='guava.jar',"
                + "licenses=['LICENSE'],"
                + "source_jar='guava-sources.jar',"
                + "visibility=['PUBLIC'],"
                + ")"));

    thrown.expect(BuildFileParseException.class);

    parser.getBuildFileManifest(buildFile, new AtomicLong());
  }

  @Test
  public void detectsMissingRequiredAttribute() throws Exception {
    Path buildFile = projectFilesystem.resolve("src").resolve("test").resolve("BUCK");
    Files.createDirectories(buildFile.getParent());
    Files.write(
        buildFile,
        Arrays.asList(
            "prebuilt_jar("
                + "name='guava',"
                + "licenses=['LICENSE'],"
                + "source_jar='guava-sources.jar',"
                + "visibility=['PUBLIC'],"
                + ")"));

    thrown.expect(BuildFileParseException.class);

    parser.getBuildFileManifest(buildFile, new AtomicLong());
  }

  @Test
  public void globFunction() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Path buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory);
    Files.write(
        buildFile,
        Arrays.asList("prebuilt_jar(name='guava', binary_jar='foo.jar', licenses=glob(['f*']))"));
    Files.createFile(directory.resolve("file1"));
    Files.createFile(directory.resolve("file2"));
    Files.createFile(directory.resolve("bad_file"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(
        Type.STRING_LIST.convert(rule.get("licenses"), "license"),
        equalTo(ImmutableList.of("file1", "file2")));
  }

  @Test
  public void lazyRangeIsUsedFunction() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Path buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory);
    Files.write(
        buildFile, Arrays.asList("prebuilt_jar(name=type(range(5)), binary_jar='foo.jar')"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("name"), equalTo("range"));
  }

  @Test
  public void globManifestIsCapturedFunction() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Path buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory);
    Files.write(
        buildFile,
        Arrays.asList("prebuilt_jar(name='guava', binary_jar='foo.jar', licenses=glob(['f*']))"));
    Files.createFile(directory.resolve("file1"));
    Files.createFile(directory.resolve("file2"));
    Files.createFile(directory.resolve("bad_file"));
    BuildFileManifest buildFileManifest = parser.getBuildFileManifest(buildFile, new AtomicLong());
    assertThat(buildFileManifest.getTargets(), Matchers.hasSize(1));
    assertThat(
        buildFileManifest.getGlobManifest(),
        equalTo(
            ImmutableMap.of(
                GlobSpec.builder()
                    .setInclude(ImmutableList.of("f*"))
                    .setExclude(ImmutableList.of())
                    .setExcludeDirectories(true)
                    .build(),
                ImmutableSet.of("file1", "file2"))));
  }

  @Test
  public void readConfigFunction() throws Exception {
    Path buildFile = projectFilesystem.resolve("BUCK");
    Files.write(
        buildFile,
        Arrays.asList(
            "prebuilt_jar(name=read_config('app', 'name', 'guava'), binary_jar='foo.jar')"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("name"), equalTo("guava"));
  }

  @Test
  public void accessedUnsetConfigOptionIsRecorded() throws Exception {
    Path buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile, ImmutableList.of("val = read_config('app', 'name', 'guava')"));
    BuildFileManifest buildFileManifest = parser.getBuildFileManifest(buildFile, new AtomicLong());
    Map<String, Object> configs = buildFileManifest.getConfigs();
    assertEquals(ImmutableMap.of("app", ImmutableMap.of("name", Optional.empty())), configs);
  }

  @Test
  public void accessedSetConfigOptionIsRecorded() throws Exception {
    Path buildFile = projectFilesystem.resolve("BUCK");
    Files.write(
        buildFile,
        ImmutableList.of("val = read_config('dummy_section', 'dummy_key', 'dummy_value')"));
    BuildFileManifest buildFileManifest = parser.getBuildFileManifest(buildFile, new AtomicLong());
    Map<String, Object> configs = buildFileManifest.getConfigs();
    assertEquals(
        ImmutableMap.of("dummy_section", ImmutableMap.of("dummy_key", Optional.of("dummy_value"))),
        configs);
  }

  @Test
  public void missingRequiredAttributeIsReported() throws Exception {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(eventCollector);
    Path buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("prebuilt_jar()"));
    try {
      parser.getBuildFileManifest(buildFile, new AtomicLong());
      fail("Should not reach here.");
    } catch (BuildFileParseException e) {
      assertThat(e.getMessage(), startsWith("Cannot evaluate build file "));
    }
    assertThat(eventCollector.count(), is(1));
    assertThat(
        eventCollector.iterator().next().getMessage(),
        stringContainsInOrder(
            "prebuilt_jar requires name and binary_jar but they are not provided.",
            "Need help? See https://buckbuild.com/rule/prebuilt_jar"));
  }

  @Test
  public void packageNameFunction() throws Exception {
    Path buildFile = projectFilesystem.resolve("pkg").resolve("BUCK");
    Files.createDirectories(buildFile.getParent());
    Files.write(
        buildFile, Arrays.asList("prebuilt_jar(name=package_name(), binary_jar='foo.jar')"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("name"), equalTo("pkg"));
  }

  @Test
  public void canUsePrintInBuildFile() throws Exception {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(eventCollector);
    Path buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("print('hello world')"));
    parser.getBuildFileManifest(buildFile, new AtomicLong());
    Event printEvent = eventCollector.iterator().next();
    assertThat(printEvent.getMessage(), equalTo("hello world"));
    assertThat(printEvent.getKind(), equalTo(EventKind.DEBUG));
  }

  @Test
  public void canUsePrintInExtensionFile() throws Exception {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(eventCollector);
    Path buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("load('//:ext.bzl', 'ext')"));
    Path extensionFile = projectFilesystem.resolve("ext.bzl");
    Files.write(extensionFile, Arrays.asList("ext = 'hello'", "print('hello world')"));
    parser.getBuildFileManifest(buildFile, new AtomicLong());
    Event printEvent = eventCollector.iterator().next();
    assertThat(printEvent.getMessage(), equalTo("hello world"));
    assertThat(printEvent.getKind(), equalTo(EventKind.DEBUG));
  }

  @Test
  public void nativeFunctionUsageAtTopLevelIsReportedAsAnError() throws Exception {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(eventCollector);
    Path buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("load('//:ext.bzl', 'ext')"));
    Path extensionFile = projectFilesystem.resolve("ext.bzl");
    Files.write(extensionFile, Arrays.asList("ext = read_config('foo', 'bar')"));
    try {
      parser.getBuildFileManifest(buildFile, new AtomicLong());
      fail("Parsing should have failed.");
    } catch (BuildFileParseException e) {
      Event printEvent = eventCollector.iterator().next();
      assertThat(
          printEvent.getMessage(),
          equalTo(
              "Top-level invocations of read_config are not allowed in .bzl files. "
                  + "Wrap it in a macro and call it from a BUCK file."));
      assertThat(printEvent.getKind(), equalTo(EventKind.ERROR));
    }
  }

  @Test
  public void nativeReadConfigFunctionCanBeInvokedFromExtensionFile() throws Exception {
    Path buildFileDirectory = projectFilesystem.resolve("test");
    Files.createDirectories(buildFileDirectory);
    Path buildFile = buildFileDirectory.resolve("BUCK");
    Path extensionFileDirectory = buildFileDirectory.resolve("ext");
    Files.createDirectories(extensionFileDirectory);
    Path extensionFile = extensionFileDirectory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//test/ext:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionFile,
        Arrays.asList("def get_name():", "  return native.read_config('foo', 'bar', 'baz')"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("baz"));
  }

  @Test
  public void canLoadSameExtensionMultipleTimes() throws Exception {
    Path buildFile = projectFilesystem.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("load('//:ext.bzl', 'ext')", "load('//:ext.bzl', 'ext')"));
    Path extensionFile = projectFilesystem.resolve("ext.bzl");
    Files.write(extensionFile, Arrays.asList("ext = 'hello'", "print('hello world')"));
    assertTrue(parser.getBuildFileManifest(buildFile, new AtomicLong()).getTargets().isEmpty());
  }

  @Test
  public void packageNameFunctionInExtensionUsesBuildFilePackage() throws Exception {
    Path buildFileDirectory = projectFilesystem.resolve("test");
    Files.createDirectories(buildFileDirectory);
    Path buildFile = buildFileDirectory.resolve("BUCK");
    Path extensionFileDirectory = buildFileDirectory.resolve("ext");
    Files.createDirectories(extensionFileDirectory);
    Path extensionFile = extensionFileDirectory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//test/ext:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(extensionFile, Arrays.asList("def get_name():", "  return native.package_name()"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("test"));
  }

  @Test
  public void repositoryNameFunctionInExtensionReturnsCellName() throws Exception {
    Path buildFileDirectory = projectFilesystem.resolve("test");
    Files.createDirectories(buildFileDirectory);
    Path buildFile = buildFileDirectory.resolve("BUCK");
    Path extensionFileDirectory = buildFileDirectory.resolve("ext");
    Files.createDirectories(extensionFileDirectory);
    Path extensionFile = extensionFileDirectory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//test/ext:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionFile, Arrays.asList("def get_name():", "  return native.repository_name()"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("@"));
  }

  @Test
  public void repositoryNameFunctionInBuildFileReturnsCellName() throws Exception {
    Path buildFileDirectory = projectFilesystem.resolve("test");
    Files.createDirectories(buildFileDirectory);
    Path buildFile = buildFileDirectory.resolve("BUCK");
    Path extensionFileDirectory = buildFileDirectory.resolve("ext");
    Files.createDirectories(extensionFileDirectory);
    Files.write(buildFile, Arrays.asList("prebuilt_jar(name='foo', binary_jar=repository_name())"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("@"));
  }

  @Test
  public void testImportVariable() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'JAR')",
            "prebuilt_jar(name='foo', binary_jar=JAR)"));
    Files.write(extensionFile, Arrays.asList("JAR='jar'"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("jar"));
  }

  @Test
  public void canUseStructsInExtensionFiles() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'jar')",
            "prebuilt_jar(name='foo', binary_jar=jar)"));
    Files.write(extensionFile, Arrays.asList("s = struct(x='j',y='ar')", "jar=s.x+s.y"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("jar"));
  }

  @Test
  public void testImportFunction() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(extensionFile, Arrays.asList("def get_name():", "  return 'jar'"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("jar"));
  }

  @Test
  public void testImportFunctionUsingBuiltInRule() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList("load('//src/test:build_rules.bzl', 'guava_jar')", "guava_jar(name='foo')"));
    Files.write(
        extensionFile,
        Arrays.asList(
            "def guava_jar(name):", "  native.prebuilt_jar(name=name, binary_jar='foo.jar')"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("name"), equalTo("foo"));
    assertThat(rule.get("binaryJar"), equalTo("foo.jar"));
  }

  @Test
  public void cannotUseGlobalGlobFunctionInsideOfExtension() throws Exception {
    thrown.expect(BuildFileParseException.class);

    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList("load('//src/test:build_rules.bzl', 'guava_jar')", "guava_jar(name='foo')"));
    Files.write(
        extensionFile,
        Arrays.asList(
            "def guava_jar(name):",
            "  native.prebuilt_jar(name=name, binary_jar='foo.jar', licenses=glob(['*.txt']))"));
    getSingleRule(buildFile);
  }

  @Test
  public void canUseNativeGlobFunctionInsideOfExtension() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList("load('//src/test:build_rules.bzl', 'guava_jar')", "guava_jar(name='foo')"));
    Files.write(
        extensionFile,
        Arrays.asList(
            "def guava_jar(name):",
            "  native.prebuilt_jar(name=name, binary_jar='foo.jar', licenses=native.glob(['*.txt']))"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("name"), equalTo("foo"));
    assertThat(rule.get("binaryJar"), equalTo("foo.jar"));
    assertThat(
        Type.STRING_LIST.convert(rule.get("licenses"), "license"), equalTo(ImmutableList.of()));
  }

  @Test
  public void canUseBuiltInListFunction() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Files.write(
        buildFile,
        Arrays.asList("prebuilt_jar(name='a', binary_jar='a.jar', licenses=list(('l1', 'l2')))"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(
        Type.STRING_LIST.convert(rule.get("licenses"), "license"),
        equalTo(ImmutableList.of("l1", "l2")));
  }

  @Test
  public void canUseUnicodeChars() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("prebuilt_jar(name='β', binary_jar='a.jar')"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(Type.STRING.convert(rule.get("name"), "name"), equalTo("β"));
  }

  @Test
  public void functionDefinitionsAreNotAllowedInBuildFiles() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("def foo():", "  pass"));

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot parse build file " + buildFile);

    parser.getBuildFileManifest(buildFile, new AtomicLong());
  }

  @Test
  public void evaluationErrorIsReported() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("foo()"));

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot evaluate build file " + buildFile);

    parser.getBuildFileManifest(buildFile, new AtomicLong());
  }

  @Test
  public void extensionFileEvaluationErrorIsReported() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(buildFile, Arrays.asList("load('//src/test:build_rules.bzl', 'guava_jar')"));
    Files.write(extensionFile, Arrays.asList("error"));

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot evaluate extension file //src/test:build_rules.bzl");

    parser.getBuildFileManifest(buildFile, new AtomicLong());
  }

  @Test
  public void canUseBuiltInListFunctionInExtension() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList("load('//src/test:build_rules.bzl', 'guava_jar')", "guava_jar(name='foo')"));
    Files.write(
        extensionFile,
        Arrays.asList(
            "def guava_jar(name):",
            "  native.prebuilt_jar(name=name, binary_jar='foo.jar', licenses=list(('l1', 'l2')))"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(
        Type.STRING_LIST.convert(rule.get("licenses"), "license"),
        equalTo(ImmutableList.of("l1", "l2")));
  }

  @Test
  public void testImportFunctionFromExtension() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Path extensionExtensionFile = directory.resolve("extension_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(extensionExtensionFile, Arrays.asList("def get_name():", "  return 'jar'"));
    Files.write(extensionFile, Arrays.asList("load('//src/test:extension_rules.bzl', 'get_name')"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("jar"));
  }

  @Test
  public void testCanLoadExtensionFromBuildFileUsingRelativeLabel() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load(':build_rules.bzl', 'jar')", "prebuilt_jar(name='foo', binary_jar=jar)"));
    Files.write(extensionFile, Arrays.asList("jar = 'jar.jar'"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("jar.jar"));
  }

  @Test
  public void testCannotLoadExtensionFromANestedDirectory() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load(':foo/build_rules.bzl', 'jar')", "prebuilt_jar(name='foo', binary_jar=jar)"));
    Files.write(extensionFile, Arrays.asList("jar = 'jar.jar'"));
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Relative loads work only for files in the same directory but "
            + ":foo/build_rules.bzl is trying to load a file from a nested directory. "
            + "Please use absolute label instead ([cell]//pkg[/pkg]:target).");
    getSingleRule(buildFile);
  }

  @Test
  public void testCanLoadExtensionFromExtensionUsingRelativeLabel() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Path extensionExtensionFile = directory.resolve("extension_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(extensionExtensionFile, Arrays.asList("def get_name():", "  return 'jar'"));
    Files.write(extensionFile, Arrays.asList("load(':extension_rules.bzl', 'get_name')"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("jar"));
  }

  @Test
  public void parsingOfExtensionWithSyntacticErrorsFails() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(extensionFile, Arrays.asList("def get_name():\n  return 'jar'\nj j"));
    thrown.expectMessage("Cannot parse extension file //src/test:build_rules.bzl");
    parser.getBuildFileManifest(buildFile, new AtomicLong());
  }

  @Test
  public void canImportExtensionFromAnotherCell() throws Exception {
    Cell cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();

    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path anotherCell = projectFilesystem.resolve("tp2");
    Path extensionDirectory = anotherCell.resolve("ext");
    Files.createDirectories(extensionDirectory);
    Path extensionFile = extensionDirectory.resolve("build_rules.bzl");

    ProjectBuildFileParserOptions options =
        ProjectBuildFileParserOptions.builder()
            .setProjectRoot(cell.getRoot())
            .setAllowEmptyGlobs(ParserConfig.DEFAULT_ALLOW_EMPTY_GLOBS)
            .setIgnorePaths(ImmutableSet.of())
            .setBuildFileName("BUCK")
            .setDescriptions(knownBuildRuleTypesProvider.get(cell).getDescriptions())
            .setBuildFileImportWhitelist(ImmutableList.of())
            .setPythonInterpreter("skylark")
            .setCellRoots(ImmutableMap.of("tp2", anotherCell))
            .build();
    parser =
        SkylarkProjectBuildFileParser.using(
            options,
            BuckEventBusForTests.newInstance(),
            SkylarkFilesystem.using(projectFilesystem),
            BuckGlobals.builder()
                .setDisableImplicitNativeRules(options.getDisableImplicitNativeRules())
                .setDescriptions(options.getDescriptions())
                .setRuleFunctionFactory(new RuleFunctionFactory(new DefaultTypeCoercerFactory()))
                .build(),
            new PrintingEventHandler(EnumSet.allOf(EventKind.class)),
            NativeGlobber::create);

    Files.write(
        buildFile,
        Arrays.asList(
            "load('@tp2//ext:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(extensionFile, Arrays.asList("def get_name():\n  return 'jar'"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("name"), equalTo("foo"));
    assertThat(rule.get("binaryJar"), equalTo("jar"));
  }

  @Test
  public void attemptToLoadInvalidCellIsReported() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path anotherCell = projectFilesystem.resolve("tp2");
    Path extensionDirectory = anotherCell.resolve("ext");
    Files.createDirectories(extensionDirectory);
    Path extensionFile = extensionDirectory.resolve("build_rules.bzl");

    Files.write(
        buildFile,
        Arrays.asList(
            "load('@invalid_repo//ext:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(extensionFile, Arrays.asList("def get_name():\n  return 'jar'"));
    thrown.expectMessage(
        "@invalid_repo//ext:build_rules.bzl references an unknown repository invalid_repo");
    getSingleRule(buildFile);
  }

  @Test
  public void parseMetadataIsReturned() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(extensionFile, Arrays.asList("def get_name():", "  return 'jar'"));
    BuildFileManifest buildFileManifest = parser.getBuildFileManifest(buildFile, new AtomicLong());
    assertThat(buildFileManifest.getTargets(), Matchers.hasSize(1));
    Map<String, Object> prebuiltJarRule = buildFileManifest.getTargets().get(0);
    assertThat(prebuiltJarRule.get("name"), equalTo("foo"));
    ImmutableSet<String> includes = buildFileManifest.getIncludes();
    assertThat(
        includes
            .stream()
            .map(projectFilesystem::resolve)
            .map(Path::getFileName) // simplify matching by stripping temporary path prefixes
            .map(Object::toString)
            .collect(ImmutableList.toImmutableList()),
        equalTo(ImmutableList.of("BUCK", "build_rules.bzl")));
    Map<String, Object> configs = buildFileManifest.getConfigs();
    assertThat(configs, equalTo(ImmutableMap.of()));
    Optional<ImmutableMap<String, Optional<String>>> env = buildFileManifest.getEnv();
    assertFalse(env.isPresent());
  }

  @Test
  public void cannotUseNativeRulesImplicitlyInBuildFilesIfDisabled()
      throws IOException, InterruptedException {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("prebuilt_jar(name='foo', binary_jar='guava.jar')"));

    ProjectBuildFileParserOptions options =
        getDefaultParserOptions().setDisableImplicitNativeRules(true).build();

    parser = createParserWithOptions(eventCollector, options);
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot evaluate build file");

    try {
      parser.getBuildFileManifest(projectFilesystem.resolve(buildFile), new AtomicLong());
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
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("extension.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:extension.bzl', 'make_jar')",
            "make_jar(name='foo', binary_jar='guava.jar')"));
    Files.write(
        extensionFile,
        Arrays.asList("def make_jar(*args, **kwargs):", "    prebuilt_jar(*args, **kwargs)"));

    ProjectBuildFileParserOptions options =
        getDefaultParserOptions().setDisableImplicitNativeRules(true).build();

    parser = createParserWithOptions(eventCollector, options);
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot evaluate build file");

    try {
      parser.getBuildFileManifest(projectFilesystem.resolve(buildFile), new AtomicLong());
    } catch (BuildFileParseException e) {
      Event event = eventCollector.iterator().next();
      assertEquals(EventKind.ERROR, event.getKind());
      assertThat(event.getMessage(), containsString("name 'prebuilt_jar' is not defined"));
      assertThat(event.getMessage(), containsString("extension.bzl\", line 2, in make_jar"));
      throw e;
    }
  }

  @Test
  public void canUseNativeRulesViaNativeModuleInExtensionFilesIfDisabled()
      throws IOException, InterruptedException {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("extension.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:extension.bzl', 'make_jar')",
            "make_jar(name='foo', binary_jar='guava.jar')"));
    Files.write(
        extensionFile,
        Arrays.asList(
            "def make_jar(*args, **kwargs):", "    native.prebuilt_jar(*args, **kwargs)"));

    ProjectBuildFileParserOptions options =
        getDefaultParserOptions().setDisableImplicitNativeRules(true).build();

    parser = createParserWithOptions(new PrintingEventHandler(EventKind.ALL_EVENTS), options);

    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("name"), equalTo("foo"));
    assertThat(rule.get("binaryJar"), equalTo("guava.jar"));
  }

  @Test
  public void canUseNativeRulesImplicitlyInBuildFilesIfNotDisabled()
      throws IOException, InterruptedException {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("prebuilt_jar(name='foo', binary_jar='guava.jar')"));

    ProjectBuildFileParserOptions options =
        getDefaultParserOptions().setDisableImplicitNativeRules(false).build();

    parser = createParserWithOptions(new PrintingEventHandler(EventKind.ALL_EVENTS), options);

    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("name"), equalTo("foo"));
    assertThat(rule.get("binaryJar"), equalTo("guava.jar"));
  }

  @Test
  public void cannotUseNativeRulesImplicitlyInExtensionFilesIfNotDisabled()
      throws IOException, InterruptedException {
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("extension.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:extension.bzl', 'make_jar')",
            "make_jar(name='foo', binary_jar='guava.jar')"));
    Files.write(
        extensionFile,
        Arrays.asList("def make_jar(*args, **kwargs):", "    prebuilt_jar(*args, **kwargs)"));

    ProjectBuildFileParserOptions options =
        getDefaultParserOptions().setDisableImplicitNativeRules(false).build();

    parser = createParserWithOptions(eventCollector, options);
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot evaluate build file");

    try {
      parser.getBuildFileManifest(projectFilesystem.resolve(buildFile), new AtomicLong());
    } catch (BuildFileParseException e) {
      Event event = eventCollector.iterator().next();
      assertEquals(EventKind.ERROR, event.getKind());
      assertThat(event.getMessage(), containsString("name 'prebuilt_jar' is not defined"));
      assertThat(event.getMessage(), containsString("extension.bzl\", line 2, in make_jar"));
      throw e;
    }
  }

  @Test
  public void ruleDoesNotExistIfNotDefined() throws Exception {
    Path buildFile = projectFilesystem.resolve("pkg").resolve("BUCK");
    Files.createDirectories(buildFile.getParent());
    Files.write(
        buildFile, Arrays.asList("prebuilt_jar(name=str(rule_exists('r')), binary_jar='foo')"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("name"), equalTo("False"));
  }

  @Test
  public void ruleDoesNotExistIfNotDefinedWhenUsedFromExtensionFile() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Path extensionExtensionFile = directory.resolve("extension_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionExtensionFile,
        Arrays.asList("def get_name():", "  return str(native.rule_exists('does_not_exist'))"));
    Files.write(extensionFile, Arrays.asList("load('//src/test:extension_rules.bzl', 'get_name')"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("False"));
  }

  @Test
  public void ruleExistsIfDefined() throws Exception {
    Path buildFile = projectFilesystem.resolve("pkg").resolve("BUCK");
    Files.createDirectories(buildFile.getParent());
    Files.write(
        buildFile,
        Arrays.asList(
            "prebuilt_jar(name='foo', binary_jar='binary.jar')",
            "prebuilt_jar(name=str(rule_exists('foo')), binary_jar='foo')"));
    BuildFileManifest buildFileManifest = parser.getBuildFileManifest(buildFile, new AtomicLong());
    assertThat(buildFileManifest.getTargets(), Matchers.hasSize(2));
    Map<String, Object> rule = buildFileManifest.getTargets().get(1);
    assertThat(rule.get("name"), equalTo("True"));
  }

  @Test
  public void ruleExistsIfDefinedWhenUsedFromExtensionFile() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    Path extensionExtensionFile = directory.resolve("extension_rules.bzl");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='exists', binary_jar='binary.jar')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    Files.write(
        extensionExtensionFile,
        Arrays.asList("def get_name():", "  return str(native.rule_exists('exists'))"));
    Files.write(extensionFile, Arrays.asList("load('//src/test:extension_rules.bzl', 'get_name')"));
    BuildFileManifest buildFileManifest = parser.getBuildFileManifest(buildFile, new AtomicLong());
    assertThat(buildFileManifest.getTargets(), Matchers.hasSize(2));
    Map<String, Object> rule = buildFileManifest.getTargets().get(0);
    assertThat(rule.get("name"), is("foo"));
    assertThat(rule.get("binaryJar"), equalTo("True"));
  }

  @Test
  public void throwsHumanReadableExceptionWhenFileDoesNotExist()
      throws IOException, InterruptedException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "src/test/build_rules.bzl cannot be loaded because it does not exist. It was referenced from //src/test:BUCK");

    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Files.write(
        buildFile,
        Arrays.asList(
            "load('//src/test:build_rules.bzl', 'get_name')",
            "prebuilt_jar(name='exists', binary_jar='binary.jar')",
            "prebuilt_jar(name='foo', binary_jar=get_name())"));
    parser.getBuildFileManifest(buildFile, new AtomicLong());
  }

  private Map<String, Object> getSingleRule(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    BuildFileManifest buildFileManifest = parser.getBuildFileManifest(buildFile, new AtomicLong());
    assertThat(buildFileManifest.getTargets(), Matchers.hasSize(1));
    return buildFileManifest.getTargets().get(0);
  }
}

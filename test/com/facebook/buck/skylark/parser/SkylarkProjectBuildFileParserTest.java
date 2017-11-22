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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.plugin.BuckPluginManagerFactory;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultKnownBuildRuleTypesFactory;
import com.facebook.buck.rules.KnownBuildRuleTypesProvider;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.sandbox.TestSandboxExecutionStrategyFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.Type;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
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

  @Before
  public void setUp() throws Exception {
    projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    Cell cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
    knownBuildRuleTypesProvider =
        KnownBuildRuleTypesProvider.of(
            DefaultKnownBuildRuleTypesFactory.of(
                new DefaultProcessExecutor(new TestConsole()),
                BuckPluginManagerFactory.createPluginManager(),
                new TestSandboxExecutionStrategyFactory()));
    parser =
        SkylarkProjectBuildFileParser.using(
            ProjectBuildFileParserOptions.builder()
                .setProjectRoot(cell.getRoot())
                .setAllowEmptyGlobs(ParserConfig.DEFAULT_ALLOW_EMPTY_GLOBS)
                .setIgnorePaths(ImmutableSet.of())
                .setBuildFileName("BUCK")
                .setDescriptions(knownBuildRuleTypesProvider.get(cell).getDescriptions())
                .setBuildFileImportWhitelist(ImmutableList.of())
                .setPythonInterpreter("skylark")
                .build(),
            BuckEventBusForTests.newInstance(),
            SkylarkFilesystem.using(projectFilesystem),
            new DefaultTypeCoercerFactory());
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

    parser.getAll(buildFile, new AtomicLong());
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

    parser.getAll(buildFile, new AtomicLong());
  }

  @Test
  public void packageNameIsProvided() throws Exception {
    Path buildFile = projectFilesystem.resolve("src").resolve("test").resolve("BUCK");
    Files.createDirectories(buildFile.getParent());
    Files.write(buildFile, Arrays.asList("prebuilt_jar(name='guava', binary_jar=PACKAGE_NAME)"));

    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("src/test"));
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
  public void functionDefinitionsAreNotAllowedInBuildFiles() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("def foo():", "  pass"));

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot parse build file " + buildFile);

    parser.getAll(buildFile, new AtomicLong());
  }

  @Test
  public void evaluationErrorIsReported() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Files.write(buildFile, Arrays.asList("foo()"));

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot evaluate build file " + buildFile);

    parser.getAll(buildFile, new AtomicLong());
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
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot parse extension file //src/test:build_rules.bzl");
    parser.getAll(buildFile, new AtomicLong());
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

    parser =
        SkylarkProjectBuildFileParser.using(
            ProjectBuildFileParserOptions.builder()
                .setProjectRoot(cell.getRoot())
                .setAllowEmptyGlobs(ParserConfig.DEFAULT_ALLOW_EMPTY_GLOBS)
                .setIgnorePaths(ImmutableSet.of())
                .setBuildFileName("BUCK")
                .setDescriptions(knownBuildRuleTypesProvider.get(cell).getDescriptions())
                .setBuildFileImportWhitelist(ImmutableList.of())
                .setPythonInterpreter("skylark")
                .setCellRoots(ImmutableMap.of("tp2", anotherCell))
                .build(),
            BuckEventBusForTests.newInstance(),
            SkylarkFilesystem.using(projectFilesystem),
            new DefaultTypeCoercerFactory());

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
    thrown.expect(BuildFileParseException.class);
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
    ImmutableList<Map<String, Object>> allRulesAndMetaRules =
        parser.getAllRulesAndMetaRules(buildFile, new AtomicLong());
    assertThat(allRulesAndMetaRules, Matchers.hasSize(4));
    Map<String, Object> prebuiltJarRule = allRulesAndMetaRules.get(0);
    assertThat(prebuiltJarRule.get("name"), equalTo("foo"));
    Map<String, Object> includesMetadataRule = allRulesAndMetaRules.get(1);
    @SuppressWarnings("unchecked")
    ImmutableSet<String> includes = (ImmutableSet<String>) includesMetadataRule.get("__includes");
    assertThat(
        includes
            .stream()
            .map(projectFilesystem::resolve)
            .map(Path::getFileName) // simplify matching by stripping temporary path prefixes
            .map(Object::toString)
            .collect(MoreCollectors.toImmutableList()),
        equalTo(ImmutableList.of("BUCK", "build_rules.bzl")));
    Map<String, Object> configsMetadataRule = allRulesAndMetaRules.get(2);
    assertThat(configsMetadataRule.get("__configs"), equalTo(ImmutableMap.of()));
    Map<String, Object> envsMetadataRule = allRulesAndMetaRules.get(3);
    assertThat(envsMetadataRule.get("__env"), equalTo(ImmutableMap.of()));
  }

  private Map<String, Object> getSingleRule(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    ImmutableList<Map<String, Object>> allRules = parser.getAll(buildFile, new AtomicLong());
    assertThat(allRules, Matchers.hasSize(1));
    return allRules.get(0);
  }
}

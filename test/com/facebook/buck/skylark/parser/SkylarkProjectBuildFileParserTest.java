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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.filesystem.SkylarkFilesystem;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.Type;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws Exception {
    projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    Cell cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
    parser =
        SkylarkProjectBuildFileParser.using(
            ProjectBuildFileParserOptions.builder()
                .setProjectRoot(cell.getRoot())
                .setAllowEmptyGlobs(ParserConfig.DEFAULT_ALLOW_EMPTY_GLOBS)
                .setIgnorePaths(ImmutableSet.of())
                .setBuildFileName("BUCK")
                .setDescriptions(cell.getAllDescriptions())
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
    projectFilesystem.mkdirs(buildFile.getParent());
    projectFilesystem.writeContentsToPath(
        "prebuilt_jar("
            + "name='guava',"
            + "binary_jar='guava.jar',"
            + "licenses=['LICENSE'],"
            + "source_jar='guava-sources.jar',"
            + "visibility=['PUBLIC'],"
            + ")",
        buildFile);

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
    projectFilesystem.mkdirs(buildFile.getParent());
    projectFilesystem.writeContentsToPath(
        "prebuilt_jar("
            + "name='guava',"
            + "binary_jarz='guava.jar',"
            + "licenses=['LICENSE'],"
            + "source_jar='guava-sources.jar',"
            + "visibility=['PUBLIC'],"
            + ")",
        buildFile);

    thrown.expect(BuildFileParseException.class);

    parser.getAllRulesAndMetaRules(buildFile, new AtomicLong());
  }

  @Test
  public void detectsMissingRequiredAttribute() throws Exception {
    Path buildFile = projectFilesystem.resolve("src").resolve("test").resolve("BUCK");
    projectFilesystem.mkdirs(buildFile.getParent());
    projectFilesystem.writeContentsToPath(
        "prebuilt_jar("
            + "name='guava',"
            + "licenses=['LICENSE'],"
            + "source_jar='guava-sources.jar',"
            + "visibility=['PUBLIC'],"
            + ")",
        buildFile);

    thrown.expect(BuildFileParseException.class);

    parser.getAllRulesAndMetaRules(buildFile, new AtomicLong());
  }

  @Test
  public void packageNameIsProvided() throws Exception {
    Path buildFile = projectFilesystem.resolve("src").resolve("test").resolve("BUCK");
    projectFilesystem.mkdirs(buildFile.getParent());
    projectFilesystem.writeContentsToPath(
        "prebuilt_jar(name='guava', binary_jar=PACKAGE_NAME)", buildFile);

    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("src/test"));
  }

  @Test
  public void globFunction() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Path buildFile = directory.resolve("BUCK");
    Files.createDirectories(directory);
    projectFilesystem.writeContentsToPath(
        "prebuilt_jar(name='guava', binary_jar='foo.jar', licenses=glob(['f*']))", buildFile);
    Files.createFile(directory.resolve("file1"));
    Files.createFile(directory.resolve("file2"));
    Files.createFile(directory.resolve("bad_file"));
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(
        Type.STRING_LIST.convert(rule.get("licenses"), "license"),
        equalTo(ImmutableList.of("file1", "file2")));
  }

  @Test
  public void testImportVariable() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    projectFilesystem.writeContentsToPath(
        "load('//src/test:build_rules.bzl', 'JAR')\n" + "prebuilt_jar(name='foo', binary_jar=JAR)",
        buildFile);
    projectFilesystem.writeContentsToPath("JAR='jar'", extensionFile);
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("jar"));
  }

  @Test
  public void testImportFunction() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    projectFilesystem.writeContentsToPath(
        "load('//src/test:build_rules.bzl', 'get_name')\n"
            + "prebuilt_jar(name='foo', binary_jar=get_name())",
        buildFile);
    projectFilesystem.writeContentsToPath("def get_name():\n  return 'jar'", extensionFile);
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("jar"));
  }

  @Test
  public void testImportFunctionUsingBuiltInRule() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    projectFilesystem.writeContentsToPath(
        "load('//src/test:build_rules.bzl', 'guava_jar')\n" + "guava_jar(name='foo')", buildFile);
    projectFilesystem.writeContentsToPath(
        "def guava_jar(name):\n  native.prebuilt_jar(name=name, binary_jar='foo.jar')",
        extensionFile);
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("name"), equalTo("foo"));
    assertThat(rule.get("binaryJar"), equalTo("foo.jar"));
  }

  @Test
  public void canUseBuiltInListFunction() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    projectFilesystem.writeContentsToPath(
        "prebuilt_jar(name='a', binary_jar='a.jar', licenses=list(('l1', 'l2')))", buildFile);
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
    projectFilesystem.writeContentsToPath("def foo():\n  pass", buildFile);

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Cannot parse build file " + buildFile);

    parser.getAll(buildFile, new AtomicLong());
  }

  @Test
  public void evaluationErrorIsReported() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    projectFilesystem.writeContentsToPath("foo()", buildFile);

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
    projectFilesystem.writeContentsToPath(
        "load('//src/test:build_rules.bzl', 'guava_jar')\n" + "guava_jar(name='foo')", buildFile);
    projectFilesystem.writeContentsToPath(
        "def guava_jar(name):\n  native.prebuilt_jar(name=name, binary_jar='foo.jar', licenses=list(('l1', 'l2')))",
        extensionFile);
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
    projectFilesystem.writeContentsToPath(
        "load('//src/test:build_rules.bzl', 'get_name')\n"
            + "prebuilt_jar(name='foo', binary_jar=get_name())",
        buildFile);
    projectFilesystem.writeContentsToPath(
        "def get_name():\n  return 'jar'", extensionExtensionFile);
    projectFilesystem.writeContentsToPath(
        "load('//src/test:extension_rules.bzl', 'get_name')", extensionFile);
    Map<String, Object> rule = getSingleRule(buildFile);
    assertThat(rule.get("binaryJar"), equalTo("jar"));
  }

  @Test
  public void parsingOfExtensionWithSyntacticErrorsFails() throws Exception {
    Path directory = projectFilesystem.resolve("src").resolve("test");
    Files.createDirectories(directory);
    Path buildFile = directory.resolve("BUCK");
    Path extensionFile = directory.resolve("build_rules.bzl");
    projectFilesystem.writeContentsToPath(
        "load('//src/test:build_rules.bzl', 'get_name')\n"
            + "prebuilt_jar(name='foo', binary_jar=get_name())",
        buildFile);
    projectFilesystem.writeContentsToPath("def get_name():\n  return 'jar'\nj j", extensionFile);
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
                .setDescriptions(cell.getAllDescriptions())
                .setBuildFileImportWhitelist(ImmutableList.of())
                .setPythonInterpreter("skylark")
                .setCellRoots(ImmutableMap.of("tp2", anotherCell))
                .build(),
            BuckEventBusForTests.newInstance(),
            SkylarkFilesystem.using(projectFilesystem),
            new DefaultTypeCoercerFactory());

    projectFilesystem.writeContentsToPath(
        "load('@tp2//ext:build_rules.bzl', 'get_name')\n"
            + "prebuilt_jar(name='foo', binary_jar=get_name())",
        buildFile);
    projectFilesystem.writeContentsToPath("def get_name():\n  return 'jar'", extensionFile);
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

    projectFilesystem.writeContentsToPath(
        "load('@invalid_repo//ext:build_rules.bzl', 'get_name')\n"
            + "prebuilt_jar(name='foo', binary_jar=get_name())",
        buildFile);
    projectFilesystem.writeContentsToPath("def get_name():\n  return 'jar'", extensionFile);
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "@invalid_repo//ext:build_rules.bzl references an unknown repository invalid_repo");
    getSingleRule(buildFile);
  }

  private Map<String, Object> getSingleRule(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    ImmutableList<Map<String, Object>> allRulesAndMetaRules =
        parser.getAllRulesAndMetaRules(buildFile, new AtomicLong());
    assertThat(allRulesAndMetaRules, Matchers.hasSize(1));
    return allRulesAndMetaRules.get(0);
  }
}

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
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.Type;
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

    ImmutableList<Map<String, Object>> allRulesAndMetaRules =
        parser.getAllRulesAndMetaRules(buildFile, new AtomicLong());
    assertThat(allRulesAndMetaRules, Matchers.hasSize(1));
    Map<String, Object> rule = allRulesAndMetaRules.get(0);
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

    ImmutableList<Map<String, Object>> allRulesAndMetaRules =
        parser.getAllRulesAndMetaRules(buildFile, new AtomicLong());
    assertThat(allRulesAndMetaRules, Matchers.hasSize(1));
    Map<String, Object> rule = allRulesAndMetaRules.get(0);
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
    ImmutableList<Map<String, Object>> allRulesAndMetaRules =
        parser.getAllRulesAndMetaRules(buildFile, new AtomicLong());
    assertThat(allRulesAndMetaRules, Matchers.hasSize(1));
    Map<String, Object> rule = allRulesAndMetaRules.get(0);
    assertThat(
        Type.STRING_LIST.convert(rule.get("licenses"), "license"),
        equalTo(ImmutableList.of("file1", "file2")));
  }
}

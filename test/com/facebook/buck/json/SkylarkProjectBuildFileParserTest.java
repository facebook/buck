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

package com.facebook.buck.json;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.filesystem.SkylarkFilesystem;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class SkylarkProjectBuildFileParserTest {

  private SkylarkFilesystem skylarkFilesystem;
  private SkylarkProjectBuildFileParser parser;
  private Cell cell;
  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() throws Exception {
    // Jimfs does not currently support Windows syntax for an absolute path on the current drive
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    projectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    skylarkFilesystem = SkylarkFilesystem.using(projectFilesystem);
    cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
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
            skylarkFilesystem);
  }

  @Test
  public void canParsePrebuiltJarRule() throws Exception {
    Path buildFile =
        skylarkFilesystem.getPath(cell.getRoot().resolve("test").resolve("BUCK").toString());
    FileSystemUtils.createDirectoryAndParents(buildFile.getParentDirectory());
    FileSystemUtils.writeContentAsLatin1(
        buildFile,
        "prebuilt_jar("
            + "name='guava',"
            + "binary_jar='guava.jar',"
            + "licenses=['LICENSE'],"
            + "source_jar='guava-sources.jar',"
            + "visibility=['PUBLIC'],"
            + ")");

    ImmutableList<Map<String, Object>> allRulesAndMetaRules =
        parser.getAllRulesAndMetaRules(
            projectFilesystem.getPath(buildFile.toString()), new AtomicLong());
    assertThat(allRulesAndMetaRules, Matchers.hasSize(1));
    Map<String, Object> rule = allRulesAndMetaRules.get(0);
    assertThat(rule.get("name"), equalTo("guava"));
    assertThat(rule.get("binary_jar"), equalTo("guava.jar"));
    assertThat(
        Type.STRING_LIST.convert(rule.get("licenses"), "license"),
        equalTo(ImmutableList.of("LICENSE")));
    assertThat(rule.get("source_jar"), equalTo("guava-sources.jar"));
    assertThat(
        Type.STRING_LIST.convert(rule.get("visibility"), "PUBLIC"),
        equalTo(ImmutableList.of("PUBLIC")));
    assertThat(rule.get("buck.base_path"), equalTo("test"));
  }

  @Test
  public void includeDefsIsANop() throws Exception {
    Path buildFile =
        skylarkFilesystem.getPath(cell.getRoot().resolve("test").resolve("BUCK").toString());
    FileSystemUtils.createDirectoryAndParents(buildFile.getParentDirectory());
    FileSystemUtils.writeContentAsLatin1(buildFile, "include_defs('//foo/bar')");

    parser.getAllRulesAndMetaRules(
        projectFilesystem.getPath(buildFile.toString()), new AtomicLong());
  }
}

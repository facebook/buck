/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuckConfigTestUtils;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ParserConfigTest {

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testGetAllowEmptyGlobs() throws IOException {
    assertTrue(FakeBuckConfig.builder().build().getView(ParserConfig.class).getAllowEmptyGlobs());
    Reader reader = new StringReader(
        Joiner.on('\n').join(
            "[build]",
            "allow_empty_globs = false"));
    ParserConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader).getView(ParserConfig.class);
    assertFalse(config.getAllowEmptyGlobs());
  }

  @Test
  public void testGetGlobHandler() throws IOException {
    assertThat(
        FakeBuckConfig.builder().build().getView(ParserConfig.class).getGlobHandler(),
        Matchers.equalTo(ParserConfig.GlobHandler.PYTHON));

    for (ParserConfig.GlobHandler handler : ParserConfig.GlobHandler.values()) {
      Reader reader = new StringReader(
          Joiner.on('\n').join(
              "[project]",
              "glob_handler = " + handler.toString()));
      ParserConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(
          temporaryFolder,
          reader).getView(ParserConfig.class);
      assertThat(config.getGlobHandler(), Matchers.equalTo(handler));
    }
  }

  @Test
  public void shouldReturnThreadCountIfParallelParsingIsEnabled() {
    BuckConfig config = FakeBuckConfig.builder()
        .setSections(
            "[project]",
            "parsing_threads = 2",
            "parallel_parsing = true")
        .build();

    ParserConfig parserConfig = config.getView(ParserConfig.class);

    assertTrue(parserConfig.getEnableParallelParsing());
    assertEquals(2, parserConfig.getNumParsingThreads());
  }

  @Test
  public void shouldReturnOneThreadCountIfParallelParsingIsNotEnabled() {
    BuckConfig config = FakeBuckConfig.builder()
        .setSections(
            "[project]",
            "parsing_threads = 3",
            "parallel_parsing = false")
        .build();

    ParserConfig parserConfig = config.getView(ParserConfig.class);

    assertFalse(parserConfig.getEnableParallelParsing());
    assertEquals(1, parserConfig.getNumParsingThreads());
  }

  @Test
  public void shouldGetReadOnlyDirs() throws IOException {
    String existingPath1 = "tmp/tmp-file";
    String existingPath2 = "tmp2/tmp2-file";
    ImmutableSet<Path> readOnlyPaths = ImmutableSet.of(
        Paths.get(existingPath1),
        Paths.get(existingPath2));
    ProjectFilesystem filesystem = new FakeProjectFilesystem(readOnlyPaths);

    ParserConfig parserConfig = FakeBuckConfig.builder()
        .setSections(
            "[project]",
            "read_only_paths = " + existingPath1 + "," + existingPath2)
        .setFilesystem(filesystem)
        .build().getView(ParserConfig.class);

    assertTrue(parserConfig.getReadOnlyPaths().isPresent());
    assertEquals(
        parserConfig.getReadOnlyPaths().get(),
        ImmutableList.of(
            filesystem.resolve(Paths.get(existingPath1)),
            filesystem.resolve(Paths.get(existingPath2))));

    String notExistingDir = "not/existing/path";
    parserConfig = FakeBuckConfig.builder()
        .setSections("[project]", "read_only_paths = " + notExistingDir)
        .setFilesystem(filesystem)
        .build().getView(ParserConfig.class);

    thrown.expect(HumanReadableException.class);
    parserConfig.getReadOnlyPaths();
  }

  @Test
  public void testGetEnableBuildFileSandboxing() throws IOException {
    assertFalse(
        FakeBuckConfig.builder().build().getView(ParserConfig.class)
            .getEnableBuildFileSandboxing());

    Reader reader = new StringReader(
        Joiner.on('\n').join(
            "[project]",
            "enable_build_file_sandboxing = true"));
    ParserConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader).getView(ParserConfig.class);
    assertTrue(config.getEnableBuildFileSandboxing());

    reader = new StringReader(
        Joiner.on('\n').join(
            "[project]",
            "enable_build_file_sandboxing = false"));
    config = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader).getView(ParserConfig.class);
    assertFalse(config.getEnableBuildFileSandboxing());
  }

  @Test
  public void testGetBuildFileImportWhitelist() throws IOException {
    assertTrue(
        FakeBuckConfig.builder().build().getView(ParserConfig.class)
            .getBuildFileImportWhitelist()
            .isEmpty());

    Reader reader = new StringReader(
        Joiner.on('\n').join(
            "[project]",
            "build_file_import_whitelist = os, foo"));
    ParserConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader).getView(ParserConfig.class);
    assertEquals(ImmutableList.of("os", "foo"), config.getBuildFileImportWhitelist());
  }
}

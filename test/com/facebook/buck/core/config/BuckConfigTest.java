/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.core.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.file.MorePathsForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuckConfigTest {

  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testConstructorWithNonExistentBasePath() throws IOException {
    Reader reader =
        new StringReader(Joiner.on('\n').join("[alias]", "katana = //java/com/example:fb4a"));

    // BuckConfig should allow nonexistent targets without throwing.
    BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
  }

  @Test
  public void testResolveNullPathThatMayBeOutsideTheProjectFilesystem() throws IOException {
    BuckConfig config = createFromText("");
    assertNull(config.resolvePathThatMayBeOutsideTheProjectFilesystem(null));
  }

  @Test
  public void testResolveAbsolutePathThatMayBeOutsideTheProjectFilesystem() throws IOException {
    BuckConfig config = createFromText("");
    assertEquals(
        MorePathsForTests.rootRelativePath("foo/bar"),
        config.resolvePathThatMayBeOutsideTheProjectFilesystem(
            MorePathsForTests.rootRelativePath("foo/bar")));
  }

  @Test
  public void testResolveRelativePathThatMayBeOutsideTheProjectFilesystem() throws IOException {
    BuckConfig config = createFromText("");
    assertEquals(
        MorePathsForTests.rootRelativePath("project/foo/bar"),
        config.resolvePathThatMayBeOutsideTheProjectFilesystem(Paths.get("../foo/bar")));
  }

  @Test
  public void testResolveHomeDirPathThatMayBeOutsideTheProjectFilesystem() throws IOException {
    BuckConfig config = createFromText("");
    Path homePath = Paths.get("").getFileSystem().getPath(System.getProperty("user.home"));
    assertEquals(
        homePath.resolve("foo/bar"),
        config.resolvePathThatMayBeOutsideTheProjectFilesystem(Paths.get("~/foo/bar")));
  }

  @Test
  public void testBuckPyIgnorePaths() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "buck_py_ignore_paths", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertSuccess("buck test --all should exit cleanly");
  }

  @Test
  public void testGetAndroidTargetSdkWithSpaces() throws IOException {
    BuckConfig config = createFromText("[android]", "target = Google Inc.:Google APIs:16");
    assertEquals("Google Inc.:Google APIs:16", config.getValue("android", "target").get());
  }

  private BuckConfig createFromText(String... lines) throws IOException {
    ProjectFilesystem projectFilesystem =
        new FakeProjectFilesystem(MorePathsForTests.rootRelativePath("project/root"));
    StringReader reader = new StringReader(Joiner.on('\n').join(lines));
    return BuckConfigTestUtils.createFromReader(
        reader,
        projectFilesystem,
        Architecture.detect(),
        Platform.detect(),
        EnvVariablesProvider.getSystemEnv());
  }

  @Test
  public void hasUserDefinedValueReturnsTrueForEmptySetting() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("cache", ImmutableMap.of("mode", "")))
            .build();
    assertTrue(buckConfig.hasUserDefinedValue("cache", "mode"));
  }

  @Test
  public void hasUserDefinedValueReturnsFalseForNoSetting() {
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(ImmutableMap.of()).build();
    assertFalse(buckConfig.hasUserDefinedValue("cache", "mode"));
  }

  @Test
  public void testGetMap() throws IOException {
    Reader reader =
        new StringReader(Joiner.on('\n').join("[section]", "args_map = key0=>val0,key1=>val1"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);

    assertEquals(
        ImmutableMap.of("key0", "val0", "key1", "val1"), config.getMap("section", "args_map"));
  }

  @Test
  public void testGetMapComplex() throws IOException {
    Reader reader =
        new StringReader(
            Joiner.on('\n').join("[section]", "args_map = key0 => \"val0,val1\", key1 => val2"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
    assertEquals(
        ImmutableMap.of("key0", "val0,val1", "key1", "val2"),
        config.getConfig().getMap("section", "args_map"));
    assertEquals(
        ImmutableMap.of("key0", "val0,val1", "key1", "val2"),
        config
            .getConfig()
            .getMap(
                "section",
                "args_map",
                Config.DEFAULT_PAIR_SEPARATOR,
                Config.DEFAULT_KEY_VALUE_SEPARATOR));
  }
}

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

package com.facebook.buck.cli;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.MorePathsForTests;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;

public class BuckConfigTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  /**
   * Ensure that whichever alias is listed first in the file is the one used in the reverse map if
   * the value appears multiple times.
   */
  @Test
  public void testGetBasePathToAliasMap() throws IOException, NoSuchBuildTargetException {
    Reader reader1 = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "fb4a   =   //java/com/example:fbandroid",
        "katana =   //java/com/example:fbandroid"));
    BuckConfig config1 = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader1);
    assertEquals(
        ImmutableMap.of(Paths.get("java/com/example"), "fb4a"),
        config1.getBasePathToAliasMap());
    assertEquals(
        ImmutableMap.of(
            "fb4a", "//java/com/example:fbandroid",
            "katana", "//java/com/example:fbandroid"),
        config1.getEntriesForSection("alias"));

    Reader reader2 = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "katana =   //java/com/example:fbandroid",
        "fb4a   =   //java/com/example:fbandroid"));
    BuckConfig config2 = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader2);
    assertEquals(
        ImmutableMap.of(Paths.get("java/com/example"), "katana"),
        config2.getBasePathToAliasMap());
    assertEquals(
        ImmutableMap.of(
            "fb4a", "//java/com/example:fbandroid",
            "katana", "//java/com/example:fbandroid"),
        config2.getEntriesForSection("alias"));

    Reader noAliasesReader = new StringReader("");
    BuckConfig noAliasesConfig = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        noAliasesReader);
    assertEquals(ImmutableMap.of(), noAliasesConfig.getBasePathToAliasMap());
    assertEquals(ImmutableMap.of(), noAliasesConfig.getEntriesForSection("alias"));
  }

  @Test
  public void testConstructorThrowsForMalformedBuildTarget() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "fb4a   = :fb4a"));
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.replay(projectFilesystem);

    try {
      BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("Path in :fb4a must start with //", e.getHumanReadableErrorMessage());
    }

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void testConstructorWithNonExistentBasePath() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "katana = //java/com/example:fb4a"));

    // BuckConfig should allow nonexistent targets without throwing.
    BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
  }

  @Test
  public void testGetBuildTargetForAlias() throws IOException, NoSuchBuildTargetException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "foo = //java/com/example:foo",
        "bar = //java/com/example:bar"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader);

    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("foo"));
    assertEquals("//java/com/example:bar", config.getBuildTargetForAlias("bar"));
    // Flavors on alias.
    assertEquals("//java/com/example:foo#src_jar", config.getBuildTargetForAlias("foo#src_jar"));
    assertEquals("//java/com/example:bar#fl1,fl2", config.getBuildTargetForAlias("bar#fl1,fl2"));

    assertNull(
        "Invalid alias names, such as build targets, should be tolerated by this method.",
        config.getBuildTargetForAlias("//java/com/example:foo"));
    assertNull(config.getBuildTargetForAlias("baz"));
    assertNull(config.getBuildTargetForAlias("baz#src_jar"));

    Reader noAliasesReader = new StringReader("");
    BuckConfig noAliasesConfig = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        noAliasesReader);
    assertNull(noAliasesConfig.getBuildTargetForAlias("foo"));
    assertNull(noAliasesConfig.getBuildTargetForAlias("bar"));
    assertNull(noAliasesConfig.getBuildTargetForAlias("baz"));
  }

  /**
   * Ensures that all public methods of BuckConfig return reasonable values for an empty config.
   */
  @Test
  public void testEmptyConfig() {
    BuckConfig emptyConfig = new FakeBuckConfig();
    assertEquals(ImmutableMap.<String, String>of(), emptyConfig.getEntriesForSection("alias"));
    assertNull(emptyConfig.getBuildTargetForAlias("fb4a"));
    assertEquals(ImmutableMap.<Path, String>of(), emptyConfig.getBasePathToAliasMap());
  }

  @Test
  public void testValidateAliasName() {
    BuckConfig.validateAliasName("f");
    BuckConfig.validateAliasName("_");
    BuckConfig.validateAliasName("F");
    BuckConfig.validateAliasName("fb4a");
    BuckConfig.validateAliasName("FB4A");
    BuckConfig.validateAliasName("FB4_");

    try {
      BuckConfig.validateAliasName("");
      fail("Should have thrown HumanReadableException");
    } catch (HumanReadableException e) {
      assertEquals("Alias cannot be the empty string.", e.getHumanReadableErrorMessage());
    }

    try {
      BuckConfig.validateAliasName("42meaningOfLife");
      fail("Should have thrown HumanReadableException");
    } catch (HumanReadableException e) {
      assertEquals("Not a valid alias: 42meaningOfLife.", e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testReferentialAliases() throws IOException, NoSuchBuildTargetException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "foo            = //java/com/example:foo",
        "bar            = //java/com/example:bar",
        "foo_codename   = foo",
        "",
        "# Do not delete these: automation builds require these aliases to exist!",
        "automation_foo = foo_codename",
        "automation_bar = bar"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader);
    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("foo"));
    assertEquals("//java/com/example:bar", config.getBuildTargetForAlias("bar"));
    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("foo_codename"));
    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("automation_foo"));
    assertEquals("//java/com/example:bar", config.getBuildTargetForAlias("automation_bar"));
    assertNull(config.getBuildTargetForAlias("baz"));
  }

  @Test
  public void testUnresolvedAliasThrows() throws IOException, NoSuchBuildTargetException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "foo = //java/com/example:foo",
        "bar = food"));
    try {
      BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("No alias for: food.", e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testDuplicateAliasDefinitionThrows() throws IOException, NoSuchBuildTargetException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "foo = //java/com/example:foo",
        "foo = //java/com/example:foo"));
    try {
      BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals(
          "Throw an exception if there are duplicate definitions for an alias, " +
              "even if the values are the same.",
          "Duplicate definition for foo in the [alias] section of your .buckconfig or " +
              ".buckconfig.local.",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testExcludedLabels() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[test]",
        "excluded_labels = windows, linux"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader);

    assertEquals(
        ImmutableList.of("windows", "linux"),
        config.getDefaultRawExcludedLabelSelectors());
  }

  @Test
  public void testWifiBlacklist() throws IOException {
    BuckConfig config = createFromText(
        "[cache]",
        "dir = http",
        "blacklisted_wifi_ssids = yolocoaster");
    assertFalse(config.isWifiUsableForDistributedCache(Optional.of("yolocoaster")));
    assertTrue(config.isWifiUsableForDistributedCache(Optional.of("swagtastic")));

    config = createFromText(
        "[cache]",
        "dir = http");

    assertTrue(config.isWifiUsableForDistributedCache(Optional.of("yolocoaster")));
  }

  @Test
  public void testExpandUserHomeCacheDir() throws IOException {
    BuckConfig config = createFromText(
        "[cache]",
        "dir = ~/cache_dir");
    assertEquals(
        "User home cache directory must be expanded.",
        MorePaths.expandHomeDir(Paths.get("~/cache_dir")),
        config.getCacheDir());
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
  public void testBuckPyIgnorePaths() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "buck_py_ignore_paths", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertSuccess("buck test --all should exit cleanly");
  }

  @Test
  public void testGetDefaultTestTimeoutMillis() throws IOException {
    assertEquals(0L, new FakeBuckConfig().getDefaultTestTimeoutMillis());

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[test]",
        "timeout = 54321"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader);
    assertEquals(54321L, config.getDefaultTestTimeoutMillis());
  }

  @Test
  public void testGetMaxTraces() throws IOException {
    assertEquals(25, new FakeBuckConfig().getMaxTraces());

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[log]",
        "max_traces = 42"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader);
    assertEquals(42, config.getMaxTraces());
  }

  @Test
  public void testGetAndroidTargetSdkWithSpaces() throws IOException {
    BuckConfig config = createFromText(
        "[android]",
        "target = Google Inc.:Google APIs:16");
    assertEquals(
        "Google Inc.:Google APIs:16",
        config.getValue("android", "target").get());
  }

  @Test
  public void testCreateAnsi() {
    FakeBuckConfig windowsConfig = new FakeBuckConfig(Platform.WINDOWS);
    // "auto" on Windows is equivalent to "never".
    assertFalse(windowsConfig.createAnsi(Optional.<String>absent()).isAnsiTerminal());
    assertFalse(windowsConfig.createAnsi(Optional.of("auto")).isAnsiTerminal());
    assertTrue(windowsConfig.createAnsi(Optional.of("always")).isAnsiTerminal());
    assertFalse(windowsConfig.createAnsi(Optional.of("never")).isAnsiTerminal());

    FakeBuckConfig linuxConfig = new FakeBuckConfig(Platform.LINUX);
    // We don't test "auto" on Linux, because the behavior would depend on how the test was run.
    assertTrue(linuxConfig.createAnsi(Optional.of("always")).isAnsiTerminal());
    assertFalse(linuxConfig.createAnsi(Optional.of("never")).isAnsiTerminal());
  }

  @Test
  public void getEnvUsesSuppliedEnvironment() {
    String name = "SOME_ENVIRONMENT_VARIABLE";
    String value = "SOME_VALUE";
    FakeBuckConfig config = new FakeBuckConfig(
        ImmutableMap.<String, ImmutableMap<String, String>>of(),
        ImmutableMap.of(name, value));
    String[] expected = {value};
    assertArrayEquals("Should match value in environment.", expected, config.getEnv(name, ":"));
  }

  private BuckConfig createFromText(String... lines) throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem() {
      @Override
      public Path getRootPath() {
        return MorePathsForTests.rootRelativePath("project/root");
      }
    };
    StringReader reader = new StringReader(Joiner.on('\n').join(lines));
    return BuckConfigTestUtils.createFromReader(
        reader,
        projectFilesystem,
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));
  }

  @Test
  public void testShouldSetNumberOfThreadsFromBuckConfig() {
    BuckConfig buckConfig = new FakeBuckConfig(ImmutableMap.of(
        "build",
        ImmutableMap.of("threads", "3")));
    assertThat(buckConfig.getNumThreads(), Matchers.equalTo(3));
  }

  @Test
  public void testDefaultsNumberOfBuildThreadsToOneAndAQuarterTheNumberOfAvailableProcessors() {
    BuckConfig buckConfig = new FakeBuckConfig();
    assertThat(
        buckConfig.getNumThreads(),
        Matchers.equalTo((int) (Runtime.getRuntime().availableProcessors() * 1.25)));
  }

  @Test
  public void testDefaultsNumberOfBuildThreadsSpecified() {
    BuckConfig buckConfig = new FakeBuckConfig();
    assertThat(buckConfig.getNumThreads(42), Matchers.equalTo(42));
  }
}

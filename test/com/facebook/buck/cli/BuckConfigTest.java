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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.IdentityPathAbsolutifier;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.easymock.EasyMock;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;

public class BuckConfigTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void testSortOrder() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "one =   //foo:one",
        "two =   //foo:two",
        "three = //foo:three",
        "four  = //foo:four"));
    Map<String, Map<String, String>> sectionsToEntries = BuckConfig.createFromReaders(
        ImmutableList.of(reader));
    Map<String, String> aliases = sectionsToEntries.get("alias");

    // Verify that entries are sorted in the order that they appear in the file, rather than in
    // alphabetical order, or some sort of hashed-key order.
    Iterator<Map.Entry<String, String>> entries = aliases.entrySet().iterator();

    Map.Entry<String, String> first = entries.next();
    assertEquals("one", first.getKey());

    Map.Entry<String, String> second = entries.next();
    assertEquals("two", second.getKey());

    Map.Entry<String, String> third = entries.next();
    assertEquals("three", third.getKey());

    Map.Entry<String, String> fourth = entries.next();
    assertEquals("four", fourth.getKey());

    assertFalse(entries.hasNext());
  }

  @Test
  public void shouldGetBooleanValues() throws IOException {
    assertTrue(
        "a.b is true when 'yes'",
        createFromText("[a]", "  b = yes").getBooleanValue("a", "b", true));
    assertTrue(
        "a.b is true when literally 'true'",
        createFromText("[a]", "  b = true").getBooleanValue("a", "b", true));
    assertTrue(
        "a.b is true when 'YES' (capitalized)",
        createFromText("[a]", "  b = YES").getBooleanValue("a", "b", true));
    assertFalse(
        "a.b is false by default",
        createFromText("[x]", "  y = COWS").getBooleanValue("a", "b", false));
    assertFalse(
        "a.b is true when 'no'",
        createFromText("[a]", "  b = no").getBooleanValue("a", "b", true));
  }

  /**
   * Ensure that whichever alias is listed first in the file is the one used in the reverse map if
   * the value appears multiple times.
   */
  @Test
  public void testGetBasePathToAliasMap() throws IOException, NoSuchBuildTargetException {
    BuildTargetParser parser = new BuildTargetParser();
    Reader reader1 = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "fb4a   =   //java/com/example:fbandroid",
        "katana =   //java/com/example:fbandroid"));
    BuckConfig config1 = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader1,
        parser);
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
        reader2,
        parser);
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
        noAliasesReader,
        parser);
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
      BuildTargetParser parser = new BuildTargetParser();
      BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader, parser);
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals(":fb4a must start with //", e.getHumanReadableErrorMessage());
    }

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void testConstructorWithNonExistentBasePath() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "katana = //java/com/example:fb4a"));

    // BuckConfig should allow nonexistent targets without throwing.
    BuildTargetParser parser = new BuildTargetParser();
    BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader, parser);
  }

  @Test
  public void testGetBuildTargetForAlias() throws IOException, NoSuchBuildTargetException {
    BuildTargetParser parser = new BuildTargetParser();
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "foo = //java/com/example:foo",
        "bar = //java/com/example:bar"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader,
        parser);
    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("foo"));
    assertEquals("//java/com/example:bar", config.getBuildTargetForAlias("bar"));
    assertNull(
        "Invalid alias names, such as build targets, should be tolerated by this method.",
        config.getBuildTargetForAlias("//java/com/example:foo"));
    assertNull(config.getBuildTargetForAlias("baz"));

    Reader noAliasesReader = new StringReader("");
    BuckConfig noAliasesConfig = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        noAliasesReader,
        parser);
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
    BuildTargetParser parser = new BuildTargetParser();

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
        reader,
        parser);
    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("foo"));
    assertEquals("//java/com/example:bar", config.getBuildTargetForAlias("bar"));
    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("foo_codename"));
    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("automation_foo"));
    assertEquals("//java/com/example:bar", config.getBuildTargetForAlias("automation_bar"));
    assertNull(config.getBuildTargetForAlias("baz"));
  }

  @Test
  public void testUnresolvedAliasThrows() throws IOException, NoSuchBuildTargetException {
    BuildTargetParser parser = new BuildTargetParser();

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "foo = //java/com/example:foo",
        "bar = food"));
    try {
      BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader, parser);
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("No alias for: food.", e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testDuplicateAliasDefinitionThrows() throws IOException, NoSuchBuildTargetException {
    BuildTargetParser parser = new BuildTargetParser();

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "foo = //java/com/example:foo",
        "foo = //java/com/example:foo"));
    try {
      BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader, parser);
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
        reader,
        null);

    assertEquals(
        ImmutableList.of("windows", "linux"),
        config.getDefaultRawExcludedLabelSelectors());
  }

  @Test
  public void testIgnorePaths() throws IOException {
    ProjectFilesystem filesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(filesystem.getAbsolutifier())
        .andReturn(IdentityPathAbsolutifier.getIdentityAbsolutifier())
        .times(2);
    EasyMock.replay(filesystem);

    BuildTargetParser parser = new BuildTargetParser();
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[project]",
        "ignore = .git, foo, bar/, baz//, a/b/c"));
    BuckConfig config = BuckConfigTestUtils.createFromReader(
        reader,
        filesystem,
        parser,
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));

    ImmutableSet<Path> ignorePaths = config.getIgnorePaths();
    assertEquals("Should ignore paths, sans trailing slashes", ignorePaths,
        MorePaths.asPaths(ImmutableSet.of(
          BuckConstant.BUCK_OUTPUT_DIRECTORY,
          ".idea",
          System.getProperty(BuckConfig.BUCK_BUCKD_DIR_KEY, ".buckd"),
          config.getCacheDir().toString(),
          ".git",
          "foo",
          "bar",
          "baz",
          "a/b/c")));

    EasyMock.verify(filesystem);
  }

  @Test
  public void testIgnorePathsWithRelativeCacheDir() throws IOException {
    ProjectFilesystem filesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(filesystem.getAbsolutifier())
        .andReturn(IdentityPathAbsolutifier.getIdentityAbsolutifier());
    BuildTargetParser parser = EasyMock.createMock(BuildTargetParser.class);
    EasyMock.replay(filesystem, parser);

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[cache]",
        "dir = cache_dir"));
    BuckConfig config = BuckConfigTestUtils.createFromReader(
        reader,
        filesystem,
        parser,
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));

    ImmutableSet<Path> ignorePaths = config.getIgnorePaths();
    assertTrue("Relative cache directory should be in set of ignored paths",
        ignorePaths.contains(Paths.get("cache_dir")));

    EasyMock.verify(filesystem, parser);
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
        Paths.get("/foo/bar"),
        config.resolvePathThatMayBeOutsideTheProjectFilesystem(Paths.get("/foo/bar")));
  }

  @Test
  public void testResolveRelativePathThatMayBeOutsideTheProjectFilesystem() throws IOException {
    BuckConfig config = createFromText("");
    assertEquals(
        Paths.get("/project/foo/bar"),
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
        reader,
        null);
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
        reader,
        null);
    assertEquals(42, config.getMaxTraces());
  }

  @Test
  public void testOverride() throws IOException {
    Reader readerA = new StringReader(Joiner.on('\n').join(
        "[cache]",
        "    mode = dir,cassandra"));
    Reader readerB = new StringReader(Joiner.on('\n').join(
        "[cache]",
        "    mode ="));
    // Verify that no exception is thrown when a definition is overridden.
    BuckConfig.createFromReaders(ImmutableList.of(readerA, readerB));
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
    FakeBuckConfig config = new FakeBuckConfig(ImmutableMap.of(name, value));
    String[] expected = {value};
    assertArrayEquals("Should match value in environment.", expected, config.getEnv(name, ":"));
  }

  private static enum TestEnum {
    A,
    B
  }

  @Test
  public void getEnum() {
    FakeBuckConfig config = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of("section",
            ImmutableMap.of("field", "A")));
    Optional<TestEnum> value = config.getEnum("section", "field", TestEnum.class);
    assertEquals(Optional.of(TestEnum.A), value);
  }

  @Test
  public void getEnumLowerCase() {
    FakeBuckConfig config = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of("section",
            ImmutableMap.of("field", "a")));
    Optional<TestEnum> value = config.getEnum("section", "field", TestEnum.class);
    assertEquals(Optional.of(TestEnum.A), value);
  }

  @Test(expected = HumanReadableException.class)
  public void getEnumInvalidValue() {
    FakeBuckConfig config = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of("section",
            ImmutableMap.of("field", "C")));
    config.getEnum("section", "field", TestEnum.class);
  }

  private BuckConfig createFromText(String... lines) throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem() {
      @Override
      public Path getRootPath() {
        return Paths.get("/project/root");
      }
    };
    BuildTargetParser parser = new BuildTargetParser();
    StringReader reader = new StringReader(Joiner.on('\n').join(lines));
    return BuckConfigTestUtils.createFromReader(
        reader,
        projectFilesystem,
        parser,
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));
  }

}

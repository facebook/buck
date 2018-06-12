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

package com.facebook.buck.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.file.MorePathsForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuckConfigTest {

  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  /**
   * Ensure that whichever alias is listed first in the file is the one used in the reverse map if
   * the value appears multiple times.
   */
  @Test
  public void testGetBasePathToAliasMap()
      throws InterruptedException, IOException, NoSuchBuildTargetException {
    Reader reader1 =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[alias]",
                    "fb4a   =   //java/com/example:fbandroid",
                    "katana =   //java/com/example:fbandroid"));
    BuckConfig config1 = BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader1);
    assertEquals(
        ImmutableMap.of(Paths.get("java/com/example"), "fb4a"), config1.getBasePathToAliasMap());
    assertEquals(
        ImmutableMap.of(
            "fb4a", "//java/com/example:fbandroid",
            "katana", "//java/com/example:fbandroid"),
        config1.getEntriesForSection("alias"));

    Reader reader2 =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[alias]",
                    "katana =   //java/com/example:fbandroid",
                    "fb4a   =   //java/com/example:fbandroid"));
    BuckConfig config2 = BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader2);
    assertEquals(
        ImmutableMap.of(Paths.get("java/com/example"), "katana"), config2.getBasePathToAliasMap());
    assertEquals(
        ImmutableMap.of(
            "fb4a", "//java/com/example:fbandroid",
            "katana", "//java/com/example:fbandroid"),
        config2.getEntriesForSection("alias"));

    Reader noAliasesReader = new StringReader("");
    BuckConfig noAliasesConfig =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, noAliasesReader);
    assertEquals(ImmutableMap.of(), noAliasesConfig.getBasePathToAliasMap());
    assertEquals(ImmutableMap.of(), noAliasesConfig.getEntriesForSection("alias"));
  }

  @Test
  public void testGetAliasesThrowsForMalformedBuildTarget() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join("[alias]", "fb4a   = :fb4a"));
    BuckConfig buckConfig =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
    try {
      buckConfig.getAliases();
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("Path in :fb4a must start with //", e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testConstructorWithNonExistentBasePath() throws InterruptedException, IOException {
    Reader reader =
        new StringReader(Joiner.on('\n').join("[alias]", "katana = //java/com/example:fb4a"));

    // BuckConfig should allow nonexistent targets without throwing.
    BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
  }

  @Test
  public void testGetBuildTargetForAlias()
      throws InterruptedException, IOException, NoSuchBuildTargetException {
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[alias]",
                    "foo = //java/com/example:foo",
                    "bar = //java/com/example:bar",
                    "baz = //java/com/example:foo //java/com/example:bar",
                    "bash = "));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);

    assertEquals(
        ImmutableSet.of("//java/com/example:foo"), config.getBuildTargetForAliasAsString("foo"));
    assertEquals(
        ImmutableSet.of("//java/com/example:bar"), config.getBuildTargetForAliasAsString("bar"));
    assertEquals(
        ImmutableSet.of("//java/com/example:foo", "//java/com/example:bar"),
        config.getBuildTargetForAliasAsString("baz"));
    assertEquals(ImmutableSet.of(), config.getBuildTargetForAliasAsString("bash"));

    // Flavors on alias.
    assertEquals(
        ImmutableSet.of("//java/com/example:foo#src_jar"),
        config.getBuildTargetForAliasAsString("foo#src_jar"));
    assertEquals(
        ImmutableSet.of("//java/com/example:bar#fl1,fl2"),
        config.getBuildTargetForAliasAsString("bar#fl1,fl2"));
    assertEquals(
        ImmutableSet.of("//java/com/example:foo#fl1,fl2", "//java/com/example:bar#fl1,fl2"),
        config.getBuildTargetForAliasAsString("baz#fl1,fl2"));
    assertEquals(ImmutableSet.of(), config.getBuildTargetForAliasAsString("bash#fl1,fl2"));

    assertEquals(
        "Invalid alias names, such as build targets, should be tolerated by this method.",
        ImmutableSet.of(),
        config.getBuildTargetForAliasAsString("//java/com/example:foo"));
    assertEquals(ImmutableSet.of(), config.getBuildTargetForAliasAsString("notathing"));
    assertEquals(ImmutableSet.of(), config.getBuildTargetForAliasAsString("notathing#src_jar"));

    Reader noAliasesReader = new StringReader("");
    BuckConfig noAliasesConfig =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, noAliasesReader);
    assertEquals(ImmutableSet.of(), noAliasesConfig.getBuildTargetForAliasAsString("foo"));
    assertEquals(ImmutableSet.of(), noAliasesConfig.getBuildTargetForAliasAsString("bar"));
    assertEquals(ImmutableSet.of(), noAliasesConfig.getBuildTargetForAliasAsString("baz"));
  }

  @Test
  public void testGetBuildTargetListResolvesAliases()
      throws InterruptedException, IOException, NoSuchBuildTargetException {
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[alias]",
                    "foo = //java/com/example:foo",
                    "[section]",
                    "some_list = \\",
                    "foo, \\",
                    "//java/com/example:bar"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);

    ImmutableList<String> expected =
        ImmutableList.of("//java/com/example:foo", "//java/com/example:bar");
    ImmutableList<String> result =
        ImmutableList.copyOf(
            FluentIterable.from(config.getBuildTargetList("section", "some_list"))
                .transform(Object::toString));
    assertThat(result, Matchers.equalTo(expected));
  }

  /** Ensures that all public methods of BuckConfig return reasonable values for an empty config. */
  @Test
  public void testEmptyConfig() {
    BuckConfig emptyConfig = FakeBuckConfig.builder().build();
    assertEquals(ImmutableMap.<String, String>of(), emptyConfig.getEntriesForSection("alias"));
    assertEquals(ImmutableSet.of(), emptyConfig.getBuildTargetForAliasAsString("fb4a"));
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
  public void testReferentialAliases()
      throws InterruptedException, IOException, NoSuchBuildTargetException {
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[alias]",
                    "foo            = //java/com/example:foo",
                    "bar            = //java/com/example:bar",
                    "foo_codename   = foo",
                    "",
                    "# Do not delete these: automation builds require these aliases to exist!",
                    "automation_foo = foo_codename",
                    "automation_bar = bar"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
    assertEquals(
        ImmutableSet.of("//java/com/example:foo"), config.getBuildTargetForAliasAsString("foo"));
    assertEquals(
        ImmutableSet.of("//java/com/example:bar"), config.getBuildTargetForAliasAsString("bar"));
    assertEquals(
        ImmutableSet.of("//java/com/example:foo"),
        config.getBuildTargetForAliasAsString("foo_codename"));
    assertEquals(
        ImmutableSet.of("//java/com/example:foo"),
        config.getBuildTargetForAliasAsString("automation_foo"));
    assertEquals(
        ImmutableSet.of("//java/com/example:bar"),
        config.getBuildTargetForAliasAsString("automation_bar"));
    assertEquals(ImmutableSet.of(), config.getBuildTargetForAliasAsString("baz"));
  }

  @Test
  public void testUnresolvedAliasThrows()
      throws InterruptedException, IOException, NoSuchBuildTargetException {
    Reader reader =
        new StringReader(
            Joiner.on('\n').join("[alias]", "foo = //java/com/example:foo", "bar = food"));
    BuckConfig buckConfig =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
    try {
      buckConfig.getAliases();
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("No alias for: food.", e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testDuplicateAliasDefinitionThrows()
      throws InterruptedException, IOException, NoSuchBuildTargetException {
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join("[alias]", "foo = //java/com/example:foo", "foo = //java/com/example:foo"));
    try {
      BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals(
          "Throw an exception if there are duplicate definitions for an alias, "
              + "even if the values are the same.",
          String.format(
              "Duplicate definition for foo in the [alias] section of %s.",
              temporaryFolder.getRoot().resolve(".buckconfig")),
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testExcludedLabels() throws InterruptedException, IOException {
    Reader reader =
        new StringReader(Joiner.on('\n').join("[test]", "excluded_labels = windows, linux"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);

    assertEquals(
        ImmutableList.of("windows", "linux"), config.getDefaultRawExcludedLabelSelectors());
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
  public void testGetDefaultTestTimeoutMillis() throws InterruptedException, IOException {
    assertEquals(0L, FakeBuckConfig.builder().build().getDefaultTestTimeoutMillis());

    Reader reader = new StringReader(Joiner.on('\n').join("[test]", "timeout = 54321"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
    assertEquals(54321L, config.getDefaultTestTimeoutMillis());
  }

  @Test
  public void testGetAndroidTargetSdkWithSpaces() throws IOException {
    BuckConfig config = createFromText("[android]", "target = Google Inc.:Google APIs:16");
    assertEquals("Google Inc.:Google APIs:16", config.getValue("android", "target").get());
  }

  @Test
  public void testCreateAnsi() {
    BuckConfig windowsConfig =
        FakeBuckConfig.builder()
            .setArchitecture(Architecture.X86_64)
            .setPlatform(Platform.WINDOWS)
            .build();
    // "auto" on Windows is equivalent to "never".
    assertFalse(windowsConfig.createAnsi(Optional.empty()).isAnsiTerminal());
    assertFalse(windowsConfig.createAnsi(Optional.of("auto")).isAnsiTerminal());
    assertTrue(windowsConfig.createAnsi(Optional.of("always")).isAnsiTerminal());
    assertFalse(windowsConfig.createAnsi(Optional.of("never")).isAnsiTerminal());

    BuckConfig linuxConfig =
        FakeBuckConfig.builder()
            .setArchitecture(Architecture.I386)
            .setPlatform(Platform.LINUX)
            .build();
    // We don't test "auto" on Linux, because the behavior would depend on how the test was run.
    assertTrue(linuxConfig.createAnsi(Optional.of("always")).isAnsiTerminal());
    assertFalse(linuxConfig.createAnsi(Optional.of("never")).isAnsiTerminal());
  }

  @Test
  public void getEnvUsesSuppliedEnvironment() {
    String name = "SOME_ENVIRONMENT_VARIABLE";
    String value = "SOME_VALUE";
    BuckConfig config =
        FakeBuckConfig.builder().setEnvironment(ImmutableMap.of(name, value)).build();
    String[] expected = {value};
    assertArrayEquals("Should match value in environment.", expected, config.getEnv(name, ":"));
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
        ImmutableMap.copyOf(System.getenv()));
  }

  @Test
  public void testShouldSetNumberOfThreadsFromBuckConfig() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("threads", "3")))
            .build();
    assertThat(buckConfig.getNumThreads(), Matchers.equalTo(3));
  }

  @Test
  public void testDefaultsNumberOfBuildThreadsToOneAndAQuarterTheNumberOfAvailableProcessors() {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    assertThat(
        buckConfig.getNumThreads(), Matchers.equalTo(Runtime.getRuntime().availableProcessors()));
  }

  @Test
  public void testDefaultsNumberOfBuildThreadsSpecified() {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    assertThat(buckConfig.getNumThreads(42), Matchers.equalTo(42));
  }

  @Test
  public void testBuildThreadsRatioSanityCheck() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "1")))
            .build();
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(10), Matchers.equalTo(10));
  }

  @Test
  public void testBuildThreadsRatioGreaterThanZero() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "0.00001")))
            .build();
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(1), Matchers.equalTo(1));
  }

  @Test
  public void testBuildThreadsRatioRoundsUp() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "0.3")))
            .build();
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(4), Matchers.equalTo(2));
  }

  @Test
  public void testNonZeroBuildThreadsRatio() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "0.1")))
            .build();
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(1), Matchers.equalTo(1));
  }

  @Test
  public void testZeroBuildThreadsRatio() {
    try {
      BuckConfig buckConfig =
          FakeBuckConfig.builder()
              .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "0")))
              .build();
      buckConfig.getDefaultMaximumNumberOfThreads(1);
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          Matchers.startsWith("thread_core_ratio must be greater than zero"));
    }
  }

  @Test
  public void testLessThanZeroBuildThreadsRatio() {
    try {
      BuckConfig buckConfig =
          FakeBuckConfig.builder()
              .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "-0.1")))
              .build();
      buckConfig.getDefaultMaximumNumberOfThreads(1);
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          Matchers.startsWith("thread_core_ratio must be greater than zero"));
    }
  }

  @Test
  public void testBuildThreadsRatioWithReservedCores() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build",
                    ImmutableMap.of(
                        "thread_core_ratio", "1",
                        "thread_core_ratio_reserved_cores", "2")))
            .build();
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(10), Matchers.equalTo(8));
  }

  @Test
  public void testCappedBuildThreadsRatio() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build",
                    ImmutableMap.of(
                        "thread_core_ratio", "0.5",
                        "thread_core_ratio_max_threads", "4")))
            .build();
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(10), Matchers.equalTo(4));
  }

  @Test
  public void testFloorLimitedBuildThreadsRatio() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build",
                    ImmutableMap.of(
                        "thread_core_ratio", "0.25",
                        "thread_core_ratio_min_threads", "6")))
            .build();
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(10), Matchers.equalTo(6));
  }

  @Test
  public void testTestThreadUtilizationRatioDefaultValue() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("threads", "10")))
            .build();
    assertThat(buckConfig.getNumTestThreads(), Matchers.equalTo(10));
  }

  @Test
  public void testTestThreadUtilizationRatioRoundsUp() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "10"),
                    "test", ImmutableMap.of("thread_utilization_ratio", "0.51")))
            .build();
    assertThat(buckConfig.getNumTestThreads(), Matchers.equalTo(6));
  }

  @Test
  public void testTestThreadUtilizationRatioGreaterThanZero() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "1"),
                    "test", ImmutableMap.of("thread_utilization_ratio", "0.00001")))
            .build();
    assertThat(buckConfig.getNumTestThreads(), Matchers.equalTo(1));
  }

  @Test
  public void testTestThreadUtilizationRatioZero() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(Matchers.startsWith("thread_utilization_ratio must be greater than zero"));
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "1"),
                    "test", ImmutableMap.of("thread_utilization_ratio", "0")))
            .build();
    buckConfig.getNumTestThreads();
  }

  @Test
  public void testTestThreadUtilizationRatioLessThanZero() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(Matchers.startsWith("thread_utilization_ratio must be greater than zero"));
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "1"),
                    "test", ImmutableMap.of("thread_utilization_ratio", "-0.00001")))
            .build();
    buckConfig.getNumTestThreads();
  }

  @Test
  public void testEqualsForDaemonRestart() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "3"),
                    "cxx", ImmutableMap.of("cc", "/some_location/gcc")))
            .build();
    BuckConfig buckConfigMoreThreads =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "4"),
                    "cxx", ImmutableMap.of("cc", "/some_location/gcc")))
            .build();
    BuckConfig buckConfigDifferentCompiler =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "3"),
                    "cxx", ImmutableMap.of("cc", "/some_location/clang")))
            .build();

    assertFalse(buckConfig.equals(buckConfigMoreThreads));
    assertFalse(buckConfig.equals(buckConfigDifferentCompiler));

    assertTrue(buckConfig.equalsForDaemonRestart(buckConfigMoreThreads));
    assertFalse(buckConfig.equalsForDaemonRestart(buckConfigDifferentCompiler));
    assertFalse(buckConfigMoreThreads.equalsForDaemonRestart(buckConfigDifferentCompiler));
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

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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.file.MorePathsForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
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
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuckConfigTest {

  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testConstructorWithNonExistentBasePath() throws InterruptedException, IOException {
    Reader reader =
        new StringReader(Joiner.on('\n').join("[alias]", "katana = //java/com/example:fb4a"));

    // BuckConfig should allow nonexistent targets without throwing.
    BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
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

/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.apple.projectV2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.features.halide.HalideLibraryBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class BuildConfigurationTest {

  private ProjectFilesystem projectFilesystem;
  private Cells cell;
  private BuildTarget fooBuildTarget;

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);

    projectFilesystem = new FakeProjectFilesystem(new DefaultClock());
    cell = (new TestCellBuilder()).setFilesystem(projectFilesystem).build();

    fooBuildTarget = BuildTargetFactory.newInstance("//bar:foo");
  }

  @Test
  public void testWriteBuildConfigurationsForTarget() throws IOException {
    TargetNode fooTargetNode = AppleLibraryBuilder.createBuilder(fooBuildTarget).build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooTargetNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();

    ImmutableXCodeNativeTargetAttributes.Builder nativeTargetAttributes =
        ImmutableXCodeNativeTargetAttributes.builder()
            .setAppleConfig(AppleProjectHelper.createDefaultAppleConfig(projectFilesystem));
    ImmutableSet.Builder<String> targetConfigNamesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<Path> xcconfigPathsBuilder = ImmutableSet.builder();

    ImmutableMap<String, String> overrideBuildSettings =
        ImmutableMap.<String, String>builder().put("cxxFlag", "override").build();
    ImmutableMap<String, String> buckXcodeBuildSettings =
        ImmutableMap.<String, String>builder().put("REPO_ROOT", "/this_is_your_repo").build();
    ImmutableMap<String, String> appendBuildSettings =
        ImmutableMap.<String, String>builder().put("cxxFlag", "append").build();

    BuildConfiguration.writeBuildConfigurationsForTarget(
        fooTargetNode,
        fooBuildTarget,
        CxxPlatformUtils.DEFAULT_PLATFORM,
        pathResolver,
        nativeTargetAttributes,
        overrideBuildSettings,
        buckXcodeBuildSettings,
        appendBuildSettings,
        projectFilesystem,
        false,
        targetConfigNamesBuilder,
        xcconfigPathsBuilder);

    assertEquals(3, nativeTargetAttributes.build().xcconfigs().size());
    assertEquals(
        ImmutableSet.of(
            BuildConfiguration.DEBUG_BUILD_CONFIGURATION_NAME,
            BuildConfiguration.PROFILE_BUILD_CONFIGURATION_NAME,
            BuildConfiguration.RELEASE_BUILD_CONFIGURATION_NAME),
        targetConfigNamesBuilder.build());
    for (Path xcconfigPath : xcconfigPathsBuilder.build()) {
      assertTrue(
          projectFilesystem.exists(projectFilesystem.getRootPath().relativize(xcconfigPath)));
    }
  }

  @Test
  public void testGetBuildConfigurationsForAppleTargetNode() {
    TargetNode fooTargetNode = AppleLibraryBuilder.createBuilder(fooBuildTarget).build();

    ImmutableSortedMap<String, ImmutableMap<String, String>> buildConfigs =
        BuildConfiguration.getBuildConfigurationsForTargetNode(fooTargetNode);
    verifyExpectedBuildConfigurationsExist(buildConfigs, Optional.empty());
  }

  @Test
  public void testGetBuildConfigurationsForAppleTargetNodeHasTargetInlineConfig() {
    ImmutableSortedMap.Builder<String, ImmutableMap<String, String>> testConfigsBuilder =
        ImmutableSortedMap.naturalOrder();

    ImmutableMap<String, String> debugConfig =
        ImmutableMap.<String, String>builder().put("someKey", "someValue").build();
    testConfigsBuilder.put(BuildConfiguration.DEBUG_BUILD_CONFIGURATION_NAME, debugConfig);

    TargetNode fooTargetNode =
        AppleLibraryBuilder.createBuilder(fooBuildTarget)
            .setConfigs(testConfigsBuilder.build())
            .build();

    ImmutableSortedMap<String, ImmutableMap<String, String>> buildConfigs =
        BuildConfiguration.getBuildConfigurationsForTargetNode(fooTargetNode);

    verifyExpectedBuildConfigurationsExist(buildConfigs, Optional.empty());
    verifyBuildConfigurationExists(
        buildConfigs, BuildConfiguration.DEBUG_BUILD_CONFIGURATION_NAME, debugConfig);
  }

  @Test
  public void testGetBuildConfigurationsForHalideTargetNode() throws IOException {
    TargetNode fooTargetNode = new HalideLibraryBuilder(fooBuildTarget).build();

    ImmutableSortedMap<String, ImmutableMap<String, String>> buildConfigs =
        BuildConfiguration.getBuildConfigurationsForTargetNode(fooTargetNode);
    verifyExpectedBuildConfigurationsExist(buildConfigs, Optional.empty());
  }

  @Test
  public void testGetBuildConfigurationsForCxxTargetNode() {
    TargetNode fooTargetNode = new CxxLibraryBuilder(fooBuildTarget).build();

    ImmutableSortedMap<String, ImmutableMap<String, String>> buildConfigs =
        BuildConfiguration.getBuildConfigurationsForTargetNode(fooTargetNode);
    verifyExpectedBuildConfigurationsExist(buildConfigs, Optional.empty());
  }

  @Test
  public void testMergeSettings() {
    ImmutableMap<String, String> configSettings =
        ImmutableMap.<String, String>builder().put("baseFlag", "baseValue").build();
    ImmutableMap<String, String> cxxPlatformBuildSettings =
        ImmutableMap.<String, String>builder().put("cxxFlag", "original").build();
    ImmutableMap<String, String> overrideBuildSettings =
        ImmutableMap.<String, String>builder().put("cxxFlag", "override").build();
    ImmutableMap<String, String> buckXcodeBuildSettings =
        ImmutableMap.<String, String>builder().put("REPO_ROOT", "/this_is_your_repo").build();
    ImmutableMap<String, String> appendBuildSettings =
        ImmutableMap.<String, String>builder().put("cxxFlag", "append").build();

    ImmutableSortedMap<String, String> mergedSettings =
        BuildConfiguration.mergeBuildSettings(
            configSettings,
            cxxPlatformBuildSettings,
            overrideBuildSettings,
            buckXcodeBuildSettings,
            appendBuildSettings);

    assertEquals(mergedSettings.get("REPO_ROOT"), "/this_is_your_repo");
    assertEquals(mergedSettings.get("baseFlag"), "baseValue");
    assertEquals(mergedSettings.get("cxxFlag"), "$(inherited) append");
  }

  @Test
  public void testGetConfigurationXcconfigPath() {
    Path xcconfigPath =
        BuildConfiguration.getXcconfigPath(
            projectFilesystem, fooBuildTarget, BuildConfiguration.DEBUG_BUILD_CONFIGURATION_NAME);
    assertEquals(
        BuildTargetPaths.getGenPath(projectFilesystem, fooBuildTarget, "%s-Debug.xcconfig"),
        xcconfigPath);
  }

  @Test
  public void testWriteBuildConfiguration() throws IOException, InterruptedException {
    ImmutableSortedMap<String, String> config =
        ImmutableSortedMap.<String, String>naturalOrder().put("SOME_FLAG", "value").build();
    Path filePath = Paths.get("new-dir/test.xcconfig");

    BuildConfiguration.writeBuildConfiguration(projectFilesystem, filePath, config, false);
    Optional<String> fileContents = projectFilesystem.readFileIfItExists(filePath);
    assertEquals("SOME_FLAG = value\n", fileContents.get());

    // Verify that the same file is not rewritten.
    FileTime modifiedTime = projectFilesystem.getLastModifiedTime(filePath);
    BuildConfiguration.writeBuildConfiguration(projectFilesystem, filePath, config, false);
    FileTime newModifiedTime = projectFilesystem.getLastModifiedTime(filePath);
    assertEquals(modifiedTime, newModifiedTime);

    Thread.sleep(100);

    // Verify that a new file is
    config =
        ImmutableSortedMap.<String, String>naturalOrder()
            .put("SOME_FLAG", "value new y'all")
            .build();
    BuildConfiguration.writeBuildConfiguration(projectFilesystem, filePath, config, false);
    newModifiedTime = projectFilesystem.getLastModifiedTime(filePath);
    assertNotEquals(modifiedTime, newModifiedTime);
  }

  private void verifyExpectedBuildConfigurationsExist(
      ImmutableSortedMap<String, ImmutableMap<String, String>> buildConfigs,
      Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>>
          expectedAdditionalConfigs) {
    ImmutableSortedMap<String, ImmutableMap<String, String>> additionalConfigs =
        ImmutableSortedMap.of();
    if (expectedAdditionalConfigs.isPresent()) {
      additionalConfigs = expectedAdditionalConfigs.get();
    }

    assertEquals(3 + additionalConfigs.size(), buildConfigs.size());
    assertTrue(buildConfigs.keySet().contains(BuildConfiguration.DEBUG_BUILD_CONFIGURATION_NAME));
    assertTrue(buildConfigs.keySet().contains(BuildConfiguration.PROFILE_BUILD_CONFIGURATION_NAME));
    assertTrue(buildConfigs.keySet().contains(BuildConfiguration.RELEASE_BUILD_CONFIGURATION_NAME));

    for (Map.Entry<String, ImmutableMap<String, String>> entry : additionalConfigs.entrySet()) {
      assertTrue(buildConfigs.keySet().contains(entry.getKey()));
      assertEquals(entry.getValue(), buildConfigs.get(entry.getKey()));
    }
  }

  private void verifyBuildConfigurationExists(
      ImmutableSortedMap<String, ImmutableMap<String, String>> buildConfigs,
      String configName,
      ImmutableMap<String, String> buildSettings) {
    assertTrue(buildConfigs.keySet().contains(configName));
    assertEquals(buildConfigs.get(configName), buildSettings);
  }
}

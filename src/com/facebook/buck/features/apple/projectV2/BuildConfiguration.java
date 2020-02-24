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

import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.features.halide.HalideLibraryDescriptionArg;
import com.facebook.buck.io.MoreProjectFilesystems;
import com.facebook.buck.io.file.MorePosixFilePermissions;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.MoreMaps;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Helper methods to process xcconfig build configuration for a target node. */
public class BuildConfiguration {

  public static final String DEBUG_BUILD_CONFIGURATION_NAME = "Debug";
  public static final String PROFILE_BUILD_CONFIGURATION_NAME = "Profile";
  public static final String RELEASE_BUILD_CONFIGURATION_NAME = "Release";

  /**
   * Write xcconfig build configuration files for each configuration available for a target.
   * TODO(chatatap): Investigate scenarios where the buildTarget cannot be derived from the
   * targetNode.
   *
   * @param targetNode The target node for which to write the build configurations.
   * @param buildTarget The build target for which the configuration will be set.
   * @param cxxPlatform The CxxPlatform from which to obtain default build settings.
   * @param pathResolver Source path resolver to resolve args from CxxPlatform.
   * @param nativeTargetAttributes The native target builder to mutate.
   * @param overrideBuildSettings Build settings that will override ones defined elsewhere.
   * @param buckXcodeBuildSettings Buck specific build settings that will be merged.
   * @param appendBuildSettings Build settings to be appended to ones defined elsewhere.
   * @param projectFilesystem The project filesystem to which to write the xcconfig file.
   * @param writeReadOnlyFile Whether to write a read only xcconfig file.
   * @param targetConfigNamesBuilder The target config name builder to mutate.
   * @param xcconfigPathsBuilder The xcconfig paths builder to mutate.
   * @throws IOException
   */
  static void writeBuildConfigurationsForTarget(
      TargetNode<?> targetNode,
      BuildTarget buildTarget,
      CxxPlatform cxxPlatform,
      SourcePathResolverAdapter pathResolver,
      ImmutableXCodeNativeTargetAttributes.Builder nativeTargetAttributes,
      ImmutableMap<String, String> overrideBuildSettings,
      ImmutableMap<String, String> buckXcodeBuildSettings,
      ImmutableMap<String, String> appendBuildSettings,
      ProjectFilesystem projectFilesystem,
      boolean writeReadOnlyFile,
      ImmutableSet.Builder<String> targetConfigNamesBuilder,
      ImmutableSet.Builder<Path> xcconfigPathsBuilder)
      throws IOException {

    ImmutableMap<String, ImmutableMap<String, String>> targetConfigs =
        getBuildConfigurationsForTargetNode(targetNode);

    ImmutableMap<String, String> cxxPlatformBuildSettings =
        BuildConfiguration.getTargetCxxBuildConfigurationForTargetNode(
            targetNode, appendBuildSettings, cxxPlatform, pathResolver);

    // Generate build configurations for each config, using the build settings provided.
    for (Map.Entry<String, ImmutableMap<String, String>> config : targetConfigs.entrySet()) {
      targetConfigNamesBuilder.add(config.getKey());

      ImmutableSortedMap<String, String> mergedSettings =
          mergeBuildSettings(
              config.getValue(),
              cxxPlatformBuildSettings,
              overrideBuildSettings,
              buckXcodeBuildSettings,
              appendBuildSettings);

      ProjectFilesystem buildTargetFileSystem = targetNode.getFilesystem();
      // Get the Xcconfig path relative to the target's file system; this makes sure that each
      // buck-out gen path is appropriate depending on the cell.
      Path buildTargetXcconfigPath =
          getXcconfigPath(buildTargetFileSystem, buildTarget, config.getKey());
      // Now we relativize the path based around the project file system in order for relative paths
      // in Xcode to resolve properly (since they are relative to the project).
      RelPath xcconfigPath =
          projectFilesystem.relativize(buildTargetFileSystem.resolve(buildTargetXcconfigPath));

      writeBuildConfiguration(
          projectFilesystem, xcconfigPath.getPath(), mergedSettings, writeReadOnlyFile);

      nativeTargetAttributes.addXcconfigs(
          new XcconfigBaseConfiguration(config.getKey(), xcconfigPath.getPath()));

      xcconfigPathsBuilder.add(
          projectFilesystem.getPathForRelativePath(xcconfigPath.getPath()).toAbsolutePath());
    }
  }

  @VisibleForTesting
  static ImmutableSortedMap<String, ImmutableMap<String, String>>
      getBuildConfigurationsForTargetNode(TargetNode<?> targetNode) {
    Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> targetConfigs =
        Optional.empty();

    // Apple library targets and Halide library targets derive from different base descriptions
    Optional<TargetNode<AppleNativeTargetDescriptionArg>> appleTargetNode =
        TargetNodes.castArg(targetNode, AppleNativeTargetDescriptionArg.class);
    Optional<TargetNode<HalideLibraryDescriptionArg>> halideTargetNode =
        TargetNodes.castArg(targetNode, HalideLibraryDescriptionArg.class);
    if (appleTargetNode.isPresent()) {
      targetConfigs = Optional.of(appleTargetNode.get().getConstructorArg().getConfigs());
    } else if (halideTargetNode.isPresent()) {
      targetConfigs = Optional.of(halideTargetNode.get().getConstructorArg().getConfigs());
    }

    // Create empty mappings for each desired configuration
    HashMap<String, HashMap<String, String>> combinedConfig =
        new HashMap<String, HashMap<String, String>>();
    combinedConfig.put(DEBUG_BUILD_CONFIGURATION_NAME, new HashMap<String, String>());
    combinedConfig.put(PROFILE_BUILD_CONFIGURATION_NAME, new HashMap<String, String>());
    combinedConfig.put(RELEASE_BUILD_CONFIGURATION_NAME, new HashMap<String, String>());

    // Put any configuration defined on the target into the combinedConfig. CxxLibrary targets
    // configs are extracted separately in {@code getTargetCxxBuildConfigurationForTargetNode}.
    if (targetConfigs.isPresent()
        && !targetConfigs.get().isEmpty()
        && !(targetNode.getDescription() instanceof CxxLibraryDescription)) {
      for (Map.Entry<String, ImmutableMap<String, String>> config :
          targetConfigs.get().entrySet()) {
        HashMap<String, String> pendingConfig = new HashMap<String, String>(config.getValue());
        String configName = config.getKey();
        if (combinedConfig.containsKey(configName)) {
          combinedConfig.get(configName).putAll(pendingConfig);
        } else {
          combinedConfig.put(configName, pendingConfig);
        }
      }
    }

    // Create an immutable map of configs
    ImmutableSortedMap.Builder<String, ImmutableMap<String, String>> configBuilder =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<String, HashMap<String, String>> config : combinedConfig.entrySet()) {
      configBuilder.put(config.getKey(), ImmutableMap.copyOf(config.getValue()));
    }

    return configBuilder.build();
  }

  @VisibleForTesting
  private static ImmutableMap<String, String> getTargetCxxBuildConfigurationForTargetNode(
      TargetNode<?> targetNode,
      Map<String, String> appendedConfig,
      CxxPlatform defaultCxxPlatform,
      SourcePathResolverAdapter pathResolver) {
    if (targetNode.getDescription() instanceof CxxLibraryDescription) {
      return CxxPlatformBuildConfiguration.getCxxLibraryBuildSettings(
          defaultCxxPlatform, appendedConfig, pathResolver);
    } else {
      return CxxPlatformBuildConfiguration.getGenericTargetBuildSettings(
          defaultCxxPlatform, appendedConfig, pathResolver);
    }
  }

  @VisibleForTesting
  /** TODO(chatatap): Verify expected behavior and write exhausitve tests */
  static ImmutableSortedMap<String, String> mergeBuildSettings(
      ImmutableMap<String, String> configSettings,
      ImmutableMap<String, String> cxxPlatformBuildSettings,
      ImmutableMap<String, String> overrideBuildSettings,
      ImmutableMap<String, String> buckXcodeBuildSettings,
      ImmutableMap<String, String> appendBuildSettings) {
    HashMap<String, String> combinedOverrideConfigs = new HashMap<>(overrideBuildSettings);

    // If the build setting does not exist in configSettings, add it to combinedOverrideConfigs
    // with the value from platform settings.
    for (Map.Entry<String, String> entry : cxxPlatformBuildSettings.entrySet()) {
      String setting = entry.getKey();
      String existingSetting = configSettings.get(setting);
      if (existingSetting == null) {
        combinedOverrideConfigs.put(setting, entry.getValue());
      }
    }

    // If the build setting does not exist in configSettings, add it to combinedOverrideConfigs
    // with the value from buck Xcode settings.
    for (Map.Entry<String, String> entry : buckXcodeBuildSettings.entrySet()) {
      String setting = entry.getKey();
      String existingSetting = configSettings.get(setting);
      if (existingSetting == null) {
        combinedOverrideConfigs.put(setting, entry.getValue());
      }
    }

    // For all append settings, if the build setting already exists in the configSettings, use it,
    // then follow with the append setting. Otherwise set inherited and follow with the append
    // setting.
    for (Map.Entry<String, String> entry : appendBuildSettings.entrySet()) {
      String setting = entry.getKey();
      String existingSetting = configSettings.get(setting);
      String settingPrefix = existingSetting == null ? "$(inherited)" : existingSetting;
      combinedOverrideConfigs.put(setting, settingPrefix + " " + entry.getValue());
    }

    // Merge
    ImmutableSortedMap<String, String> mergedSettings =
        MoreMaps.mergeSorted(configSettings, combinedOverrideConfigs);

    return mergedSettings;
  }

  @VisibleForTesting
  static Path getXcconfigPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget, String configurationName) {
    // buck-out/gen/BUILD_TARGET_PATH.../BUILD_TARGET_SHORT_NAME-CONFIGURATION_NAME.xcconfig
    return BuildTargetPaths.getGenPath(
        projectFilesystem, buildTarget, "%s-" + configurationName + ".xcconfig");
  }

  @VisibleForTesting
  static void writeBuildConfiguration(
      ProjectFilesystem projectFilesystem,
      Path xcconfigPath,
      ImmutableSortedMap<String, String> config,
      boolean readOnly)
      throws IOException {
    StringBuilder stringBuilder = new StringBuilder();
    for (Map.Entry<String, String> entry : config.entrySet()) {
      stringBuilder.append(entry.getKey());
      stringBuilder.append(" = ");
      stringBuilder.append(entry.getValue());
      stringBuilder.append('\n');
    }
    String xcconfigContents = stringBuilder.toString();

    projectFilesystem.mkdirs(Objects.requireNonNull(xcconfigPath).getParent());
    if (MoreProjectFilesystems.fileContentsDiffer(
        new ByteArrayInputStream(xcconfigContents.getBytes(Charsets.UTF_8)),
        xcconfigPath,
        projectFilesystem)) {
      if (readOnly) {
        projectFilesystem.writeContentsToPath(
            xcconfigContents, xcconfigPath, MorePosixFilePermissions.READ_ONLY_FILE_ATTRIBUTE);
      } else {
        projectFilesystem.writeContentsToPath(xcconfigContents, xcconfigPath);
      }
    }
  }
}

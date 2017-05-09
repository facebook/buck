/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.ide.intellij;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.ide.intellij.aggregation.AggregationMode;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

public class IjProjectBuckConfig {

  private static final String PROJECT_BUCK_CONFIG_SECTION = "project";
  private static final String INTELLIJ_BUCK_CONFIG_SECTION = "intellij";

  private IjProjectBuckConfig() {}

  public static IjProjectConfig create(
      BuckConfig buckConfig,
      @Nullable AggregationMode aggregationMode,
      @Nullable String generatedFilesListFilename,
      boolean isCleanerEnabled,
      boolean removeUnusedLibraries,
      boolean excludeArtifacts,
      boolean skipBuild) {
    Optional<String> excludedResourcePathsOption =
        buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "excluded_resource_paths");

    Iterable<String> excludedResourcePaths;
    if (excludedResourcePathsOption.isPresent()) {
      excludedResourcePaths =
          Sets.newHashSet(
              Splitter.on(',')
                  .omitEmptyStrings()
                  .trimResults()
                  .split(excludedResourcePathsOption.get()));
    } else {
      excludedResourcePaths = Collections.emptyList();
    }

    Map<String, String> depToGeneratedSourcesMap =
        buckConfig.getMap(INTELLIJ_BUCK_CONFIG_SECTION, "generated_srcs_map");

    Map<String, String> labelToGeneratedSourcesMap =
        buckConfig.getMap(INTELLIJ_BUCK_CONFIG_SECTION, "generated_sources_label_map");

    Optional<Path> androidManifest =
        buckConfig.getPath(INTELLIJ_BUCK_CONFIG_SECTION, "default_android_manifest_path", false);

    return IjProjectConfig.builder()
        .setAutogenerateAndroidFacetSourcesEnabled(
            !buckConfig.getBooleanValue(
                PROJECT_BUCK_CONFIG_SECTION, "disable_r_java_idea_generator", false))
        .setJavaBuckConfig(buckConfig.getView(JavaBuckConfig.class))
        .setBuckConfig(buckConfig)
        .setProjectJdkName(buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "jdk_name"))
        .setProjectJdkType(buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "jdk_type"))
        .setAndroidModuleSdkName(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "android_module_sdk_name"))
        .setAndroidModuleSdkType(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "android_module_sdk_type"))
        .setIntellijModuleSdkName(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "intellij_module_sdk_name"))
        .setIntellijPluginLabels(
            buckConfig.getListWithoutComments(
                INTELLIJ_BUCK_CONFIG_SECTION, "intellij_plugin_labels"))
        .setJavaModuleSdkName(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "java_module_sdk_name"))
        .setJavaModuleSdkType(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "java_module_sdk_type"))
        .setProjectLanguageLevel(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "language_level"))
        .setExcludedResourcePaths(excludedResourcePaths)
        .setDepToGeneratedSourcesMap(depToGeneratedSourcesMap)
        .setLabelToGeneratedSourcesMap(labelToGeneratedSourcesMap)
        .setAndroidManifest(androidManifest)
        .setCleanerEnabled(isCleanerEnabled)
        .setRemovingUnusedLibrariesEnabled(
            isRemovingUnusedLibrariesEnabled(removeUnusedLibraries, buckConfig))
        .setExcludeArtifactsEnabled(isExcludingArtifactsEnabled(excludeArtifacts, buckConfig))
        .setSkipBuildEnabled(
            skipBuild
                || buckConfig.getBooleanValue(PROJECT_BUCK_CONFIG_SECTION, "skip_build", false))
        .setAggregationMode(getAggregationMode(aggregationMode, buckConfig))
        .setGeneratedFilesListFilename(Optional.ofNullable(generatedFilesListFilename))
        .setIgnoredTargetLabels(
            buckConfig.getListWithoutComments(
                INTELLIJ_BUCK_CONFIG_SECTION, "ignored_target_labels"))
        .build();
  }

  private static boolean isRemovingUnusedLibrariesEnabled(
      boolean removeUnusedLibraries, BuckConfig buckConfig) {
    return removeUnusedLibraries
        || buckConfig.getBooleanValue(
            INTELLIJ_BUCK_CONFIG_SECTION, "remove_unused_libraries", false);
  }

  private static boolean isExcludingArtifactsEnabled(
      boolean excludeArtifacts, BuckConfig buckConfig) {
    return excludeArtifacts
        || buckConfig.getBooleanValue(PROJECT_BUCK_CONFIG_SECTION, "exclude_artifacts", false);
  }

  private static AggregationMode getAggregationMode(
      @Nullable AggregationMode aggregationMode, BuckConfig buckConfig) {
    if (aggregationMode != null) {
      return aggregationMode;
    }
    Optional<AggregationMode> aggregationModeFromConfig =
        buckConfig
            .getValue(PROJECT_BUCK_CONFIG_SECTION, "intellij_aggregation_mode")
            .map(AggregationMode::fromString);
    return aggregationModeFromConfig.orElse(AggregationMode.AUTO);
  }
}

/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.core.build.engine.buildinfo;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

/** Shared utilities for {@link BuildInfoRecorder} and {@link OnDiskBuildInfo}. */
@VisibleForTesting
public class BuildInfo {

  public static class MetadataKey {
    /** Utility class: do not instantiate. */
    private MetadataKey() {}

    /** Key for {@link OnDiskBuildInfo} which lists the recorded items. */
    public static final String RECORDED_PATHS = "RECORDED_PATHS";

    /** Key for {@link OnDiskBuildInfo} with a map of outputs to hashes. */
    public static final String RECORDED_PATH_HASHES = "RECORDED_PATH_HASHES";

    /** Key for {@link OnDiskBuildInfo} to identify additional info describing a build. */
    public static final String ADDITIONAL_INFO = "ADDITIONAL_INFO";

    /** Key for {@link OnDiskBuildInfo} to identify the RuleKey for a build rule. */
    public static final String RULE_KEY = "RULE_KEY";

    /** Key for {@link OnDiskBuildInfo} to identify the input RuleKey for a build rule. */
    public static final String INPUT_BASED_RULE_KEY = "INPUT_BASED_RULE_KEY";

    /**
     * Key for {@link OnDiskBuildInfo} to identify the dependency-file {@link RuleKey} for a build
     * rule.
     */
    public static final String DEP_FILE_RULE_KEY = "DEP_FILE_RULE_KEY";

    /** Key for {@link OnDiskBuildInfo} to identify the dependency-file for a build rule. */
    public static final String DEP_FILE = "DEP_FILE";

    /** Key for {@link OnDiskBuildInfo} to store the build target of the owning build rule. */
    public static final String TARGET = "TARGET";

    /** Key for {@link OnDiskBuildInfo} to store the cache key of the manifest. */
    public static final String MANIFEST_KEY = "MANIFEST_KEY";

    /** Key containing the ID of the current build. */
    public static final String BUILD_ID = "BUILD_ID";

    /** Key containing the ID of the build that previously built/cached this rule's outputs. */
    public static final String ORIGIN_BUILD_ID = "ORIGIN_BUILD_ID";

    /** Key for {@link OnDiskBuildInfo} to store the size of the output. */
    public static final String OUTPUT_SIZE = "OUTPUT_SIZE";

    /** Key for {@link OnDiskBuildInfo} to store the hash of the output. */
    public static final String OUTPUT_HASH = "OUTPUT_HASH";
  }

  public static final ImmutableSet<String> METADATA_KEYS =
      ImmutableSet.of(
          MetadataKey.RECORDED_PATHS,
          MetadataKey.RECORDED_PATH_HASHES,
          MetadataKey.ADDITIONAL_INFO,
          MetadataKey.RULE_KEY,
          MetadataKey.INPUT_BASED_RULE_KEY,
          MetadataKey.DEP_FILE_RULE_KEY,
          MetadataKey.DEP_FILE,
          MetadataKey.TARGET,
          MetadataKey.MANIFEST_KEY,
          MetadataKey.BUILD_ID,
          MetadataKey.ORIGIN_BUILD_ID);

  /** All keys corresponding to rule keys. */
  public static final ImmutableSet<String> RULE_KEY_NAMES =
      ImmutableSet.of(
          MetadataKey.RULE_KEY, MetadataKey.INPUT_BASED_RULE_KEY, MetadataKey.DEP_FILE_RULE_KEY);

  /**
   * Key for {@link OnDiskBuildInfo} to store the manifest for build rules supporting manifest-based
   * caching.
   */
  public static final String MANIFEST = "MANIFEST";

  /** Utility class: do not instantiate. */
  private BuildInfo() {}

  /**
   * Returns the path to a directory where metadata files for a build rule with the specified target
   * should be stored.
   *
   * @return A path relative to the project root that includes a trailing slash.
   */
  @VisibleForTesting
  public static Path getPathToMetadataDirectory(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getScratchPath(filesystem, target, ".%s/metadata/");
  }

  public static Path getPathToArtifactMetadataDirectory(
      BuildTarget target, ProjectFilesystem filesystem) {
    return getPathToMetadataDirectory(target, filesystem).resolve("artifact");
  }

  public static Path getPathToBuildMetadataDirectory(
      BuildTarget target, ProjectFilesystem filesystem) {
    return getPathToMetadataDirectory(target, filesystem).resolve("build");
  }

  public static Path getPathToOtherMetadataDirectory(
      BuildTarget target, ProjectFilesystem filesystem) {
    return getPathToMetadataDirectory(target, filesystem).resolve("other");
  }
}

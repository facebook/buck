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

package com.facebook.buck.model;

import com.facebook.buck.util.Paths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import java.io.File;

import javax.annotation.Nullable;

public final class BuildTarget implements Comparable<BuildTarget> {

  @VisibleForTesting
  public static final String BUILD_TARGET_PREFIX = "//";

  private final File buildFile;
  private final String baseName;
  private final String shortName;
  private final String fullyQualifiedName;

  /**
   * Reserved for special internal targets such as {@link BuildTarget#ANDROID_SDK}, as well as
   * fake targets for testing.
   */
  @VisibleForTesting
  BuildTarget(String baseName, String shortName) {
    this(baseName, shortName, null /* buildFile */);
  }

  /**
    * Reserved for testing: normal constructor precondition checks are omitted.
    */
    @VisibleForTesting
        BuildTarget(String baseName, String shortName, File buildFile) {
      this.buildFile = buildFile;
      this.baseName = Preconditions.checkNotNull(baseName);
      this.shortName = Preconditions.checkNotNull(shortName);
      this.fullyQualifiedName = String.format("%s:%s", baseName, shortName);
    }

  public BuildTarget(File buildFile, String baseName, String shortName) {
    Preconditions.checkNotNull(buildFile);
    Preconditions.checkArgument(buildFile.isFile(), "%s must be a file", buildFile);
    this.buildFile = buildFile;

    Preconditions.checkNotNull(baseName);
    Preconditions.checkArgument(baseName.startsWith(BUILD_TARGET_PREFIX),
        "baseName must start with // but was %s",
        baseName);

    String parentDirectoryName = Paths.normalizePathSeparator(buildFile.getParentFile().getAbsolutePath());
    String basePath = baseName.substring(BUILD_TARGET_PREFIX.length());
    Preconditions.checkArgument(parentDirectoryName.endsWith(basePath),
        "file path %s did not end with %s for %s:%s", parentDirectoryName, basePath, baseName, shortName);
    this.baseName = baseName;

    this.shortName = Preconditions.checkNotNull(shortName);
    this.fullyQualifiedName = String.format("%s:%s", baseName, shortName);
  }

  /**
   * For use by InputRule, which is synthetic, and therefore has no buildFile.
   * @param inputFile Input file.
   * @param relativePath
   */
  private BuildTarget(File inputFile, String relativePath) {
    Preconditions.checkNotNull(inputFile);
    Preconditions.checkNotNull(relativePath);
    this.buildFile = null;
    this.baseName = String.format("//%s", relativePath);
    this.shortName = inputFile.getName();
    this.fullyQualifiedName = String.format("%s:%s", baseName, shortName);
  }

  /**
   * For exclusive use by {@link com.facebook.buck.rules.InputRule#InputRule(File, String)}.
   */
  public static BuildTarget createBuildTargetForInputFile(File inputFile, String relativePath) {
    return new BuildTarget(inputFile, relativePath);
  }

  /**
   * The build file in which this rule was defined.
   */
  @Nullable
  public File getBuildFile() {
    return buildFile;
  }

  /**
   * The directory that contains the corresponding build file.
   */
  public File getBuildFileDirectory() {
    return buildFile.getParentFile();
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "guava-latest".
   */
  public String getShortName() {
    return shortName;
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava".
   */
  public String getBaseName() {
    return baseName;
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava/".
   */
  public String getBaseNameWithSlash() {
    return getBaseNameWithSlash(baseName);
  }

  /**
   * Helper function for getting BuildTarget base names with a trailing slash if needed.
   *
   * If baseName were //third_party/java/guava, then this would return  "//third_party/java/guava/".
   * If it were //, it would return //.
   */
  @Nullable
  public static String getBaseNameWithSlash(@Nullable String baseName) {
    return baseName == null || baseName.equals(BUILD_TARGET_PREFIX) ? baseName : baseName + "/";
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "third_party/java/guava". This does not contain the "//" prefix so that it can be appended to
   * a file path.
   */
  public String getBasePath() {
    return baseName.substring(BUILD_TARGET_PREFIX.length());
  }

  /**
   * @return the value of {@link #getBasePath()} with a trailing slash, unless
   *     {@link #getBasePath()} returns the empty string, in which case this also returns the empty
   *     string
   */
  public String getBasePathWithSlash() {
    String basePath = getBasePath();
    return basePath.isEmpty() ? "" : basePath + "/";
  }

  /**
   * If this build target is //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava:guava-latest".
   */
  public String getFullyQualifiedName() {
    return fullyQualifiedName;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof BuildTarget)) {
      return false;
    }
    BuildTarget that = (BuildTarget)o;
    return Objects.equal(this.buildFile, that.buildFile)
        && Objects.equal(this.baseName, that.baseName)
        && Objects.equal(this.shortName, that.shortName);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(buildFile, baseName, shortName);
  }

  /** @return {@link #getFullyQualifiedName()} */
  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  @Override
  public int compareTo(BuildTarget target) {
    Preconditions.checkNotNull(target);
    return getFullyQualifiedName().compareTo(target.getFullyQualifiedName());
  }
}

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

import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
public final class BuildTarget implements Comparable<BuildTarget> {

  public static final String BUILD_TARGET_PREFIX = "//";

  private static final Pattern VALID_FLAVOR_PATTERN = Pattern.compile("[a-zA-Z_]+");

  private final String baseName;
  private final String shortName;
  private final Optional<String> flavor;
  private final String fullyQualifiedName;

  public BuildTarget(String baseName, String shortName) {
    this(baseName, shortName, /* flavor */ Optional.<String>absent());
  }

  public BuildTarget(String baseName, String shortName, String flavor) {
    this(baseName, shortName, Optional.of(flavor));
  }

  private BuildTarget(String baseName, String shortName, Optional<String> flavor) {
    Preconditions.checkNotNull(baseName);
    // shortName may be the empty string when parsing visibility patterns.
    Preconditions.checkNotNull(shortName);
    Preconditions.checkNotNull(flavor);

    Preconditions.checkArgument(baseName.startsWith(BUILD_TARGET_PREFIX),
        "baseName must start with // but was %s",
        baseName);

    Preconditions.checkArgument(!shortName.contains("#"),
        "Build target name cannot contain '#' but was: %s.",
        shortName);
    if (flavor.isPresent()) {
      String flavorName = flavor.get();
      if (!VALID_FLAVOR_PATTERN.matcher(flavorName).matches()) {
        throw new IllegalArgumentException("Invalid flavor: " + flavorName);
      }
      shortName += "#" + flavorName;
    }

    // On Windows, baseName may contain backslashes, which are not permitted by BuildTarget.
    this.baseName = baseName.replace("\\", "/");
    this.shortName = shortName;
    this.flavor = flavor;
    this.fullyQualifiedName = baseName + ":" + shortName;
  }

  /**
   * The build file in which this rule was defined.
   * @throws MissingBuildFileException if the build file for the target does not exist.
   */
  public File getBuildFile(ProjectFilesystem projectFilesystem) throws MissingBuildFileException {
    String pathToBuildFile = getBasePathWithSlash() + BuckConstant.BUILD_RULES_FILE_NAME;
    File buildFile = projectFilesystem.getFileForRelativePath(pathToBuildFile);
    if (buildFile.isFile()) {
      return buildFile;
    } else {
      throw new MissingBuildFileException(this);
    }
  }

  @SuppressWarnings("serial")
  public static class MissingBuildFileException extends BuildTargetException {

    private MissingBuildFileException(BuildTarget buildTarget) {
      super(String.format("No build file at %s when resolving target %s.",
          buildTarget.getBasePathWithSlash() + BuckConstant.BUILD_RULES_FILE_NAME,
          buildTarget.getFullyQualifiedName()));
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }
  }

  public Path getBuildFilePath() {
    return Paths.get(getBaseNameWithSlash() + BuckConstant.BUILD_RULES_FILE_NAME);
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "guava-latest".
   */
  @JsonProperty("shortName")
  public String getShortName() {
    return shortName;
  }

  @VisibleForTesting
  String getShortNameWithoutFlavor() {
    if (!isFlavored()) {
      return shortName;
    } else {
      return shortName.substring(0, shortName.length() - flavor.get().length() - 1);
    }
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava".
   */
  @JsonProperty("baseName")
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

  @JsonIgnore
  public boolean isFlavored() {
    return flavor.isPresent();
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof BuildTarget)) {
      return false;
    }
    BuildTarget that = (BuildTarget) o;
    return this.fullyQualifiedName.equals(that.fullyQualifiedName);
  }

  @Override
  public int hashCode() {
    return fullyQualifiedName.hashCode();
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

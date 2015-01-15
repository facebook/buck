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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.util.BuckConstant;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
public final class BuildTarget implements Comparable<BuildTarget>, HasBuildTarget {

  public static final String BUILD_TARGET_PREFIX = "//";

  private final Optional<String> repository;
  private final String baseName;
  private final String shortName;
  private final ImmutableSortedSet<Flavor> flavors;
  private final String fullyQualifiedName;

  private BuildTarget(
      Optional<String> repository,
      String baseName,
      String shortName,
      ImmutableSortedSet<Flavor> flavors) {

    Preconditions.checkArgument(baseName.startsWith(BUILD_TARGET_PREFIX),
        "baseName must start with %s but was %s",
        BUILD_TARGET_PREFIX,
        baseName);

    Preconditions.checkArgument(shortName.lastIndexOf("#") == -1,
        "Build target name cannot contain '#' but was: %s.",
        shortName);

    Preconditions.checkArgument(!shortName.contains("#"),
        "Build target name cannot contain '#' but was: %s.",
        shortName);

    this.repository = repository;
    // On Windows, baseName may contain backslashes, which are not permitted by BuildTarget.
    this.baseName = baseName.replace("\\", "/");
    this.shortName = shortName;
    this.flavors = flavors;
    this.fullyQualifiedName =
        (repository.isPresent() ? "@" + repository.get() : "") +
        baseName + ":" + shortName + getFlavorPostfix();
  }

  public Path getBuildFilePath() {
    return Paths.get(getBasePathWithSlash() + BuckConstant.BUILD_RULES_FILE_NAME);
  }

  @JsonProperty("repository")
  public Optional<String> getRepository() {
    return repository;
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "guava-latest". Note that the flavor of the target is included here.
   */
  public String getShortNameAndFlavorPostfix() {
    return shortName + getFlavorPostfix();
  }

  public String getFlavorPostfix() {
    if (flavors.isEmpty()) {
      return "";
    }
    return "#" + getFlavorsAsString();
  }

  @JsonProperty("shortName")
  public String getShortName() {
    return shortName;
  }

  @JsonProperty("flavor")
  private String getFlavorsAsString() {
    return Joiner.on(",").join(flavors);
  }

  public ImmutableSet<Flavor> getFlavors() {
    return flavors;
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
  @Nullable
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
  public Path getBasePath() {
    return Paths.get(baseName.substring(BUILD_TARGET_PREFIX.length()));
  }

  /**
   * @return the value of {@link #getBasePath()} with a trailing slash, unless
   *     {@link #getBasePath()} returns the empty string, in which case this also returns the empty
   *     string
   */
  public String getBasePathWithSlash() {
    String basePath = MorePaths.pathWithUnixSeparators(getBasePath());
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
    return !(flavors.isEmpty());
  }

  @JsonIgnore
  public boolean isInProjectRoot() {
    return BUILD_TARGET_PREFIX.equals(baseName);
  }

  /**
   * @return a {@link BuildTarget} that is equal to the current one, but with the default flavour.
   *     If this build target does not have a flavor, then this object will be returned.
   */
  public BuildTarget getUnflavoredTarget() {
    if (!isFlavored()) {
      return this;
    } else {
      return new BuildTarget(
          repository,
          baseName,
          shortName,
          ImmutableSortedSet.<Flavor>of());
    }
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
  public int compareTo(@Nullable BuildTarget target) {
    Preconditions.checkNotNull(target);
    return getFullyQualifiedName().compareTo(target.getFullyQualifiedName());
  }

  public static Builder builder(String baseName, String shortName) {
    return new Builder(baseName, shortName);
  }

  public static Builder builder(BuildTarget buildTarget) {
    return new Builder(buildTarget);
  }

  @Override
  public BuildTarget getBuildTarget() {
    return this;
  }

  public static class Builder {
    private Optional<String> repository = Optional.absent();
    private String baseName;
    private String shortName;
    private ImmutableSortedSet.Builder<Flavor> flavors = ImmutableSortedSet.naturalOrder();

    private Builder(String baseName, String shortName) {
      this.baseName = baseName;
      this.shortName = shortName;
    }

    private Builder(BuildTarget buildTarget) {
      this.repository = buildTarget.repository;
      this.baseName = buildTarget.baseName;
      this.shortName = buildTarget.shortName;
      this.flavors.addAll(buildTarget.flavors);
    }

    /**
     * Build targets are hashable and equality-comparable, so targets referring to the same
     * repository <strong>must</strong> use the same name. But build target syntax in BUCK files
     * does <strong>not</strong> have this property -- repository names are configured in the local
     * .buckconfig, and one project could use different naming from another. (And of course, targets
     * within an external project will need a @repo name prepended to them, to distinguish them from
     * targets in the root project.) It's the caller's responsibility to guarantee that repository
     * names are disambiguated before BuildTargets are created.
     */
    public Builder setRepository(String repo) {
      this.repository = Optional.of(repo);
      return this;
    }

    public Builder setFlavor(Flavor flavor) {
      flavors = ImmutableSortedSet.naturalOrder();
      flavors.add(flavor);
      return this;
    }

    public Builder setFlavor(String flavor) {
      return setFlavor(new Flavor(flavor));
    }

    public Builder addFlavor(Flavor flavor) {
      this.flavors.add(flavor);
      return this;
    }

    public Builder addFlavor(String flavor) {
      return addFlavor(new Flavor(flavor));
    }

    public Builder addFlavors(Iterable<Flavor> flavors) {
      this.flavors.addAll(flavors);
      return this;
    }

    public BuildTarget build() {
      return new BuildTarget(repository, baseName, shortName, flavors.build());
    }
  }
}

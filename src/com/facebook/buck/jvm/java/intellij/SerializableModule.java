/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.intellij;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;

import javax.annotation.Nullable;

@JsonInclude(Include.NON_NULL)
@VisibleForTesting
final class SerializableModule {
  @VisibleForTesting
  static final Comparator<SourceFolder> ALPHABETIZER = (a, b) -> a.getUrl().compareTo(b.getUrl());

  static final Comparator<SerializableModule> BUILDTARGET_NAME_COMARATOR =
      (a, b) -> a.target.getFullyQualifiedName().compareTo(b.target.getFullyQualifiedName());

  /**
   * Let intellij generate the gen directory to specific path.
   */
  @Nullable
  Path moduleGenPath;
  @Nullable
  @JsonProperty("moduleGenPath")
  public String getModuleGenPath() {
    return Optional.ofNullable(moduleGenPath).map(MorePaths::pathWithUnixSeparators).orElse(null);
  }

  @Nullable
  Path moduleRJavaPath;
  @Nullable
  @JsonProperty("moduleRJavaPath")
  public String getModuleRJavaPath() {
    return Optional.ofNullable(moduleRJavaPath)
        .map(MorePaths::pathWithUnixSeparators)
        .orElse(null);
  }

  @Nullable
  @JsonProperty
  String name;

  @Nullable
  Path pathToImlFile;
  @Nullable
  @JsonProperty("pathToImlFile")
  public String getPathToImlFile() {
    return Optional.ofNullable(pathToImlFile)
        .map(MorePaths::pathWithUnixSeparators)
        .orElse(null);
  }

  @JsonProperty
  List<SourceFolder> sourceFolders = Lists.newArrayList();
  @JsonProperty
  boolean isRootModule = false;
  /**
   * &lt;excludeFolder> elements must be sorted alphabetically in an .iml file.
   */
  @JsonProperty
  SortedSet<SourceFolder> excludeFolders = Sets.newTreeSet(ALPHABETIZER);
  @Nullable
  @JsonProperty
  List<SerializableDependentModule> dependencies = Lists.newArrayList();
  // ANDROID_BINARY / ANDROID_LIBRARY
  @Nullable
  @JsonProperty
  Boolean hasAndroidFacet;
  @Nullable
  @JsonProperty
  Boolean isAndroidLibraryProject;

  @Nullable
  Path proguardConfigPath;
  @Nullable
  @JsonProperty("proguardConfigPath")
  public String getProguardConfigPath() {
    return Optional.ofNullable(proguardConfigPath)
        .map(MorePaths::pathWithUnixSeparators)
        .orElse(null);
  }

  @Nullable
  Path resFolder;
  @Nullable
  @JsonProperty("resFolder")
  public String getResFolder() {
    return Optional.ofNullable(resFolder)
        .map(MorePaths::pathWithUnixSeparators)
        .orElse(null);
  }

  @Nullable
  Path assetFolder;
  @Nullable
  @JsonProperty("assetFolder")
  public String getAssetFolder() {
    return Optional.ofNullable(assetFolder)
        .map(MorePaths::pathWithUnixSeparators)
        .orElse(null);
  }

  @Nullable
  Path keystorePath;
  @Nullable
  @JsonProperty("keystorePath")
  public String getKeystorePath() {
    return Optional.ofNullable(keystorePath)
        .map(MorePaths::pathWithUnixSeparators)
        .orElse(null);
  }

  @Nullable
  Path androidManifest;
  @Nullable
  @JsonProperty("androidManifest")
  public String getAndroidManifest() {
    return Optional.ofNullable(androidManifest)
        .map(MorePaths::pathWithUnixSeparators)
        .orElse(null);
  }

  @Nullable
  Path nativeLibs;
  @Nullable
  @JsonProperty("nativeLibs")
  public String getNativeLibs() {
    return Optional.ofNullable(nativeLibs)
        .map(MorePaths::pathWithUnixSeparators)
        .orElse(null);
  }

  @Nullable
  @JsonProperty
  Boolean isIntelliJPlugin;
  // Annotation processing
  @Nullable
  @JsonProperty
  Path annotationGenPath;
  @Nullable
  @JsonProperty
  Boolean annotationGenIsForTest;

  @Nullable
  Path binaryPath;
  @Nullable
  @JsonProperty("binaryPath")
  public String getBinaryPath() {
    return Optional.ofNullable(binaryPath).map(MorePaths::pathWithUnixSeparators).orElse(null);
  }

  // In IntelliJ, options in an .iml file that correspond to file paths should be relative to the
  // location of the .iml file itself.
  final BuildRule srcRule;
  final BuildTarget target;  // For error reporting

  SerializableModule(BuildRule srcRule, BuildTarget target) {
    this.srcRule = srcRule;
    this.target = target;
  }

  Path getModuleDirectoryPath() {
    return MorePaths.getParentOrEmpty(Preconditions.checkNotNull(pathToImlFile));
  }

  boolean isAndroidModule() {
    return hasAndroidFacet != null && hasAndroidFacet;
  }

  boolean isIntelliJPlugin() {
    return isIntelliJPlugin != null && isIntelliJPlugin;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(SerializableModule.class)
        .add("sourceFolders", sourceFolders)
        .toString();
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @VisibleForTesting
  public static class SourceFolder {

    static final SourceFolder SRC = new SourceFolder("file://$MODULE_DIR$/src", false);
    static final SourceFolder GEN = new SourceFolder("file://$MODULE_DIR$/gen", false);

    @JsonProperty
    private final String url;

    @JsonProperty
    private final boolean isTestSource;

    @JsonProperty
    @Nullable
    private final String packagePrefix;

    SourceFolder(String url, boolean isTestSource) {
      this(url, isTestSource, null /* packagePrefix */);
    }

    SourceFolder(String url, boolean isTestSource, @Nullable String packagePrefix) {
      this.url = url;
      this.isTestSource = isTestSource;
      this.packagePrefix = packagePrefix;
    }

    public String getUrl() {
      return url;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof SourceFolder)) {
        return false;
      }
      SourceFolder that = (SourceFolder) obj;
      return Objects.equal(this.url, that.url) &&
          Objects.equal(this.isTestSource, that.isTestSource) &&
          Objects.equal(this.packagePrefix, that.packagePrefix);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(url, isTestSource, packagePrefix);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(SourceFolder.class)
          .add("url", url)
          .add("isTestSource", isTestSource)
          .add("packagePrefix", packagePrefix)
          .toString();
    }
  }

  @Nullable public List<SerializableDependentModule> getDependencies() {
    return dependencies;
  }

  public void setModuleDependencies(List<SerializableDependentModule> moduleDependencies) {
    this.dependencies = moduleDependencies;
  }
}

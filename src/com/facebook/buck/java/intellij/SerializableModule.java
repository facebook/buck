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

package com.facebook.buck.java.intellij;

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
import java.util.SortedSet;

import javax.annotation.Nullable;

@JsonInclude(Include.NON_NULL)
@VisibleForTesting
final class SerializableModule {
  @VisibleForTesting
  static final Comparator<SourceFolder> ALPHABETIZER = new Comparator<SourceFolder>() {

    @Override
    public int compare(SourceFolder a, SourceFolder b) {
      return a.getUrl().compareTo(b.getUrl());
    }

  };

  static final Comparator<SerializableModule> BUILDTARGET_NAME_COMARATOR =
      new Comparator<SerializableModule>() {
        @Override
        public int compare(SerializableModule a, SerializableModule b) {
          return a.target.getFullyQualifiedName().compareTo(b.target.getFullyQualifiedName());
        }
      };

  /**
   * Let intellij generate the gen directory to specific path.
   */
  @Nullable
  @JsonProperty
  Path moduleGenPath;
  @Nullable
  @JsonProperty
  Path moduleRJavaPath;
  @Nullable
  @JsonProperty
  String name;
  @Nullable
  @JsonProperty
  Path pathToImlFile;
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
  @JsonProperty
  Path proguardConfigPath;
  @Nullable
  @JsonProperty
  Path resFolder;
  @Nullable
  @JsonProperty
  Path assetFolder;
  @Nullable
  @JsonProperty
  Path keystorePath;
  @Nullable
  @JsonProperty
  Path androidManifest;
  @Nullable
  @JsonProperty
  Path nativeLibs;
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
  @JsonProperty
  Path binaryPath;

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
    static final SourceFolder TESTS = new SourceFolder("file://$MODULE_DIR$/tests", true);
    static final SourceFolder GEN = new SourceFolder("file://$MODULE_DIR$/gen", false);

    @JsonProperty
    private final String url;

    @JsonProperty
    private final boolean isTestSource;

    @JsonProperty
    private final boolean isResource;

    @JsonProperty
    @Nullable
    private final String packagePrefix;

    SourceFolder(String url, boolean isTestSource) {
      this(url, isTestSource, false, null /* packagePrefix */);
    }

    SourceFolder(String url, boolean isTestSource, @Nullable String packagePrefix) {
      this(url, isTestSource, false, packagePrefix);
    }

    SourceFolder(String url, boolean isTestSource, boolean isResource, @Nullable String packagePrefix) {
      this.url = url;
      this.isTestSource = isTestSource;
      this.isResource = isResource;
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
          Objects.equal(this.isResource, that.isResource) &&
          Objects.equal(this.packagePrefix, that.packagePrefix);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(url, isTestSource, isResource, packagePrefix);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(SourceFolder.class)
          .add("url", url)
          .add("isTestSource", isTestSource)
          .add("isResource", isResource)
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

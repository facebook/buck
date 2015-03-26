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

package com.facebook.buck.java.intellij;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.java.intellij.Project.SourceFolder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
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
final class Module {

  @VisibleForTesting
  static final Comparator<SourceFolder> ALPHABETIZER = new Comparator<SourceFolder>() {

    @Override
    public int compare(SourceFolder a, SourceFolder b) {
      return a.getUrl().compareTo(b.getUrl());
    }

  };

  static final Comparator<Module> BUILDTARGET_NAME_COMARATOR = new Comparator<Module>() {
    @Override
    public int compare(Module a, Module b) {
        return a.target.getFullyQualifiedName().compareTo(b.target.getFullyQualifiedName());
    }
  };

  // In IntelliJ, options in an .iml file that correspond to file paths should be relative to the
  // location of the .iml file itself.
  final BuildRule srcRule;
  final BuildTarget target;  // For error reporting

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
  List<DependentModule> dependencies;

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

  Module(BuildRule srcRule, BuildTarget target) {
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
}

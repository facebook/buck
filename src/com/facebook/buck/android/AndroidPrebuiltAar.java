/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.PrebuiltJar;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class AndroidPrebuiltAar extends AndroidLibrary implements HasAndroidResourceDeps {

  private final AndroidResource androidResource;
  private final Path nativeLibsDirectory;
  private final PrebuiltJar prebuiltJar;

  public AndroidPrebuiltAar(
      BuildRuleParams androidLibraryParams,
      SourcePathResolver resolver,
      Path proguardConfig,
      Path nativeLibsDirectory,
      PrebuiltJar prebuiltJar,
      AndroidResource androidResource,
      JavacOptions javacOptions) {
    super(
        androidLibraryParams,
        resolver,
        /* srcs */ ImmutableSortedSet.<SourcePath>of(),
        /* resources */ ImmutableSortedSet.<SourcePath>of(),
        Optional.of(proguardConfig),
        /* postprocessClassesCommands */ ImmutableList.<String>of(),
        ImmutableSortedSet.<BuildRule>of(prebuiltJar),
        /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        javacOptions,
        /* resourcesRoot */ Optional.<Path>absent(),
        /* manifestFile */ Optional.<SourcePath>absent(),
        /* isPrebuiltAar */ true);
    this.androidResource = androidResource;
    this.prebuiltJar = prebuiltJar;
    this.nativeLibsDirectory = nativeLibsDirectory;
  }

  @Override
  public String getRDotJavaPackage() {
    return androidResource.getRDotJavaPackage();
  }

  @Override
  @Nullable
  public Path getPathToTextSymbolsFile() {
    return androidResource.getPathToTextSymbolsFile();
  }

  @Override
  public Sha1HashCode getTextSymbolsAbiKey() {
    return androidResource.getTextSymbolsAbiKey();
  }

  @Nullable
  @Override
  public Path getRes() {
    return androidResource.getRes();
  }

  @Nullable
  @Override
  public Path getAssets() {
    return androidResource.getAssets();
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    super.addToCollector(collector);
    collector.addNativeLibsDirectory(getBuildTarget(), nativeLibsDirectory);
  }

  public Path getBinaryJar() {
    return prebuiltJar.getPathToOutputFile();
  }

}

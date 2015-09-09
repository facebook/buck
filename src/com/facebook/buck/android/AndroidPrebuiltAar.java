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
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class AndroidPrebuiltAar
    extends AndroidLibrary
    implements HasAndroidResourceDeps, HasRuntimeDeps {

  private final UnzipAar unzipAar;
  private final SourcePath nativeLibsDirectory;
  private final PrebuiltJar prebuiltJar;

  public AndroidPrebuiltAar(
      BuildRuleParams androidLibraryParams,
      SourcePathResolver resolver,
      SourcePath proguardConfig,
      SourcePath nativeLibsDirectory,
      PrebuiltJar prebuiltJar,
      UnzipAar unzipAar,
      JavacOptions javacOptions,
      Iterable<PrebuiltJar> exportedDeps) {
    super(
        androidLibraryParams,
        resolver,
        /* srcs */ ImmutableSortedSet.<SourcePath>of(),
        /* resources */ ImmutableSortedSet.<SourcePath>of(),
        Optional.of(proguardConfig),
        /* postprocessClassesCommands */ ImmutableList.<String>of(),
        /* deps */ ImmutableSortedSet.<BuildRule>naturalOrder()
            .add(prebuiltJar)
            .addAll(exportedDeps)
            .build(),
        /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        javacOptions,
        /* resourcesRoot */ Optional.<Path>absent(),
        /* mavenCoords */ Optional.<String>absent(),
        Optional.<SourcePath>of(
            new BuildTargetSourcePath(unzipAar.getBuildTarget(), unzipAar.getAndroidManifest())),
        /* isPrebuiltAar */ true);
    this.unzipAar = unzipAar;
    this.prebuiltJar = prebuiltJar;
    this.nativeLibsDirectory = nativeLibsDirectory;
  }

  @Override
  public String getRDotJavaPackage() {
    return unzipAar.getRDotJavaPackage();
  }

  @Override
  public Path getPathToTextSymbolsFile() {
    return unzipAar.getTextSymbolsFile();
  }

  @Override
  public Sha1HashCode getTextSymbolsAbiKey() {
    return unzipAar.getTextSymbolsHash();
  }

  @Override
  public SourcePath getRes() {
    return unzipAar.getResDirectory();
  }

  @Override
  public SourcePath getAssets() {
    return unzipAar.getAssetsDirectory();
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    super.addToCollector(collector);
    collector.addNativeLibsDirectory(getBuildTarget(), nativeLibsDirectory);

    collector.addResourceDirectory(getBuildTarget(), getRes());
    collector.addAssetsDirectory(getBuildTarget(), getAssets());
  }

  public PrebuiltJar getPrebuiltJar() {
    return prebuiltJar;
  }

  public Path getBinaryJar() {
    return prebuiltJar.getPathToOutput();
  }

  // This class is basically a wrapper around its android resource rule, since dependents will
  // use this interface to access the underlying R.java package, so make sure it's available when
  // a dependent is building against us.
  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>of(unzipAar);
  }

}

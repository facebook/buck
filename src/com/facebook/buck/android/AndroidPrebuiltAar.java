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

import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.PrebuiltJar;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.stream.Stream;

public class AndroidPrebuiltAar extends AndroidLibrary
    implements HasAndroidResourceDeps, HasRuntimeDeps {

  private final UnzipAar unzipAar;
  private final SourcePath nativeLibsDirectory;
  private final PrebuiltJar prebuiltJar;

  public AndroidPrebuiltAar(
      BuildRuleParams androidLibraryParams,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      SourcePath proguardConfig,
      SourcePath nativeLibsDirectory,
      PrebuiltJar prebuiltJar,
      UnzipAar unzipAar,
      JavacOptions javacOptions,
      CompileToJarStepFactory compileStepFactory,
      Iterable<PrebuiltJar> exportedDeps,
      ImmutableSortedSet<SourcePath> abiInputs) {
    super(
        androidLibraryParams.copyAppendingExtraDeps(ruleFinder.filterBuildRuleInputs(abiInputs)),
        resolver,
        ruleFinder,
        /* srcs */ ImmutableSortedSet.of(),
        /* resources */ ImmutableSortedSet.of(),
        Optional.of(proguardConfig),
        /* postprocessClassesCommands */ ImmutableList.of(),
        /* declaredDeps */ androidLibraryParams.getDeclaredDeps().get(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>naturalOrder()
            .add(prebuiltJar)
            .addAll(exportedDeps)
            .build(),
        /* providedDeps */ ImmutableSortedSet.of(),
        /* compileTimeClasspathDeps */ ImmutableSortedSet.of(prebuiltJar.getSourcePathToOutput()),
        abiInputs,
        HasJavaAbi.getClassAbiJar(androidLibraryParams.getBuildTarget()),
        javacOptions,
        /* trackClassUsage */ false,
        compileStepFactory,
        /* resourcesRoot */ Optional.empty(),
        /* mavenCoords */ Optional.empty(),
        Optional.of(
            new ExplicitBuildTargetSourcePath(
                unzipAar.getBuildTarget(), unzipAar.getAndroidManifest())),
        /* tests */ ImmutableSortedSet.of());
    this.unzipAar = unzipAar;
    this.prebuiltJar = prebuiltJar;
    this.nativeLibsDirectory = nativeLibsDirectory;
  }

  @Override
  public String getRDotJavaPackage() {
    return unzipAar.getRDotJavaPackage();
  }

  @Override
  public SourcePath getPathToTextSymbolsFile() {
    return new ExplicitBuildTargetSourcePath(
        unzipAar.getBuildTarget(), unzipAar.getTextSymbolsFile());
  }

  @Override
  public SourcePath getPathToRDotJavaPackageFile() {
    return new ExplicitBuildTargetSourcePath(
        unzipAar.getBuildTarget(), unzipAar.getPathToRDotJavaPackageFile());
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

  public SourcePath getBinaryJar() {
    return prebuiltJar.getSourcePathToOutput();
  }

  // This class is basically a wrapper around its android resource rule, since dependents will
  // use this interface to access the underlying R.java package, so make sure it's available when
  // a dependent is building against us.
  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    return Stream.of(unzipAar.getBuildTarget());
  }
}

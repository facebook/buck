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

package com.facebook.buck.android;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.java.DefaultJavaLibrary;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.SourcePath;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

import javax.annotation.Nullable;

public class AndroidLibrary extends DefaultJavaLibrary {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, LIBRARY);

  /**
   * Manifest to associate with this rule. Ultimately, this will be used with the upcoming manifest
   * generation logic.
   */
  private final Optional<Path> manifestFile;
  private final BuildRuleParams buildRuleParams;
  // Potentially modified as we build the enhanced deps.
  private JavacOptions javacOptions;

  @VisibleForTesting
  public AndroidLibrary(
      BuildRuleParams buildRuleParams,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<Path> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      Set<BuildRule> exportedDeps,
      JavacOptions javacOptions,
      Optional<Path> manifestFile) {
    super(buildRuleParams,
        srcs,
        resources,
        proguardConfig,
        postprocessClassesCommands,
        exportedDeps,
        javacOptions);
    this.buildRuleParams = Preconditions.checkNotNull(buildRuleParams);
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.manifestFile = Preconditions.checkNotNull(manifestFile);
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  public Optional<Path> getManifestFile() {
    return manifestFile;
  }

  @Nullable
  @Override
  public ImmutableSortedSet<BuildRule> getEnhancedDeps(BuildRuleResolver ruleResolver) {
    // The enhanced deps should be based on the combined deps and exported deps. When we stored the
    // reference to the buildruleparams, we hadn't added the exported deps. This was done in our
    // superclass's constructor.
    BuildRuleParams params = buildRuleParams.copyWithChangedDeps(getDeps());

    AndroidLibraryGraphEnhancer.Result result = new AndroidLibraryGraphEnhancer(
        params.getBuildTarget(),
        params,
        javacOptions)
        .createBuildableForAndroidResources(
            ruleResolver,
            /* createBuildableIfEmptyDeps */ false);

    Optional<DummyRDotJava> uberRDotJava = result.getOptionalDummyRDotJava();
    this.additionalClasspathEntries = uberRDotJava.isPresent()
        ? ImmutableSet.of(uberRDotJava.get().getRDotJavaBinFolder())
        : ImmutableSet.<Path>of();

    deps = result.getBuildRuleParams().getDeps();
    return deps;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    if (manifestFile.isPresent()) {
      return ImmutableList.<Path>builder()
          .addAll(super.getInputsToCompareToOutput())
          .add(manifestFile.get())
          .build();
    } else {
      return super.getInputsToCompareToOutput();
    }
  }
}

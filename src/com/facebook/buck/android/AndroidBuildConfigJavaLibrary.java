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

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.JarBuildStepsFactory;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.java.RemoveClassesPatternsMatcher;
import com.facebook.buck.jvm.java.ZipArchiveDependencySupplier;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildDeps;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Optional;

/**
 * {@link JavaLibrary} that wraps the output of an {@link AndroidBuildConfig}.
 *
 * <p>This is a custom subclass of {@link DefaultJavaLibrary} so that it can have special behavior
 * when being traversed by an {@link AndroidPackageableCollector}.
 */
class AndroidBuildConfigJavaLibrary extends DefaultJavaLibrary implements AndroidPackageable {

  private final AndroidBuildConfig androidBuildConfig;

  AndroidBuildConfigJavaLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Javac javac,
      JavacOptions javacOptions,
      ZipArchiveDependencySupplier abiClasspath,
      AndroidBuildConfig androidBuildConfig) {
    super(
        buildTarget,
        projectFilesystem,
        new BuildDeps(
            ImmutableSortedSet.copyOf(
                Iterables.concat(
                    params.getBuildDeps(), ruleFinder.filterBuildRuleInputs(abiClasspath.get())))),
        resolver,
        new JarBuildStepsFactory(
            projectFilesystem,
            ruleFinder,
            buildTarget,
            new JavacToJarStepFactory(
                resolver,
                ruleFinder,
                projectFilesystem,
                javac,
                javacOptions,
                ExtraClasspathProvider.EMPTY),
            /* srcs */ ImmutableSortedSet.of(androidBuildConfig.getSourcePathToOutput()),
            /* resources */ ImmutableSortedSet.of(),
            /* resourcesRoot */ Optional.empty(),
            /* manifest file */ Optional.empty(),
            /* postprocessClassesCommands */ ImmutableList.of(),
            abiClasspath,
            /* trackClassUsage */ javacOptions.trackClassUsage(),
            /* compileTimeClasspathDeps */ ImmutableSortedSet.of(
                androidBuildConfig.getSourcePathToOutput()),
            /* classesToRemoveFromJar */ RemoveClassesPatternsMatcher.EMPTY,
            AbiGenerationMode.CLASS,
            AbiGenerationMode.CLASS,
            /* sourceOnlyAbiRuleInfo */ null),
        /* proguardConfig */ Optional.empty(),
        /* firstOrderPackageableDeps */ params.getDeclaredDeps().get(),
        /* exportedDeps */ ImmutableSortedSet.of(),
        /* providedDeps */ ImmutableSortedSet.of(),
        HasJavaAbi.getClassAbiJar(buildTarget),
        /* sourceOnlyAbiJar */ null,
        /* mavenCoords */ Optional.empty(),
        /* tests */ ImmutableSortedSet.of(),
        /* requiredForSourceOnlyAbi */ false);
    this.androidBuildConfig = androidBuildConfig;
    Preconditions.checkState(
        params.getBuildDeps().contains(androidBuildConfig),
        "%s must depend on the AndroidBuildConfig whose output is in this rule's srcs.",
        buildTarget);
  }

  /**
   * If an {@link AndroidPackageableCollector} is traversing this rule for an {@link AndroidBinary},
   * then it should flag itself as a class that should not be dexed and insert a new classpath entry
   * for a {@code BuildConfig} with the final values for the APK.
   */
  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addBuildConfig(
        androidBuildConfig.getJavaPackage(), androidBuildConfig.getBuildConfigFields());
  }

  public AndroidBuildConfig getAndroidBuildConfig() {
    return androidBuildConfig;
  }
}

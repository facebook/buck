/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

public class CalculateAbiFromSource extends AbstractBuildRule
    implements CalculateAbi, InitializableFromDisk<Object>, SupportsInputBasedRuleKey {
  private final SourcePathRuleFinder ruleFinder;

  @AddToRuleKey private final JavacToJarStepFactory compileStepFactory;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> resources;

  @AddToRuleKey(stringify = true)
  private final Optional<Path> resourcesRoot;

  @AddToRuleKey private final Optional<SourcePath> manifestFile;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths;
  @AddToRuleKey private final ImmutableSet<Pattern> classesToRemoveFromJar;

  private final Optional<Path> outputJar;
  private final JarContentsSupplier outputJarContents;

  public CalculateAbiFromSource(
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths,
      JavacToJarStepFactory compileStepFactory,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      ImmutableSet<Pattern> classesToRemoveFromJar) {
    super(params);

    this.ruleFinder = ruleFinder;
    this.srcs = srcs;
    this.resources = resources;
    this.compileTimeClasspathSourcePaths = compileTimeClasspathSourcePaths;
    this.compileStepFactory = compileStepFactory;
    compileStepFactory.setCompileAbi();
    this.resourcesRoot = resourcesRoot;
    this.manifestFile = manifestFile;
    this.classesToRemoveFromJar = classesToRemoveFromJar;

    if (!srcs.isEmpty() || !resources.isEmpty() || manifestFile.isPresent()) {
      this.outputJar =
          Optional.of(
              BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "lib__%s__output")
                  .resolve(String.format("%s-abi.jar", getBuildTarget().getShortName())));
    } else {
      this.outputJar = Optional.empty();
    }
    this.outputJarContents =
        new JarContentsSupplier(new SourcePathResolver(ruleFinder), getSourcePathToOutput());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    JavaLibraryRules.addCompileToJarSteps(
        context,
        buildableContext,
        this,
        outputJar,
        ruleFinder,
        srcs,
        resources,
        ImmutableList.of(),
        compileTimeClasspathSourcePaths,
        false,
        null,
        compileStepFactory,
        resourcesRoot,
        manifestFile,
        classesToRemoveFromJar,
        steps);

    return steps.build();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return outputJar.map(o -> new ExplicitBuildTargetSourcePath(getBuildTarget(), o)).orElse(null);
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJarContents() {
    return outputJarContents.get();
  }

  @Override
  public Object initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    // Warm up the jar contents. We just wrote the thing, so it should be in the filesystem cache
    outputJarContents.load();
    return new Object();
  }

  @Override
  public BuildOutputInitializer<Object> getBuildOutputInitializer() {
    return new BuildOutputInitializer<>(getBuildTarget(), this);
  }
}

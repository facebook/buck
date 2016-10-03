/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.kotlin;

import static com.facebook.buck.jvm.common.ResourceValidator.validateResources;

import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.regex.Pattern;


public class KotlinLibraryDescription implements Description<KotlinLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("kotlin_library");

  private final KotlinBuckConfig kotlinBuckConfig;

  public KotlinLibraryDescription(KotlinBuckConfig kotlinBuckConfig) {
    this.kotlinBuckConfig = kotlinBuckConfig;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    BuildTarget abiJarTarget = params.getBuildTarget().withAppendedFlavors(CalculateAbi.FLAVOR);

    ImmutableSortedSet<BuildRule> exportedDeps = resolver.getAllRules(args.exportedDeps.get());
    DefaultJavaLibrary defaultJavaLibrary =
        resolver.addToIndex(
            new DefaultJavaLibrary(
                params.appendExtraDeps(
                    BuildRules.getExportedRules(
                        Iterables.concat(
                            params.getDeclaredDeps().get(),
                            exportedDeps,
                            resolver.getAllRules(args.providedDeps.get())))),
                pathResolver,
                args.srcs.get(),
                validateResources(
                    pathResolver,
                    params.getProjectFilesystem(),
                    args.resources.get()),
                Optional.<Path>absent(),
                Optional.<SourcePath>absent(),
                ImmutableList.<String>of(),
                exportedDeps,
                resolver.getAllRules(args.providedDeps.get()),
                new BuildTargetSourcePath(abiJarTarget),
                /* trackClassUsage */ false,
                /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
                new KotlincToJarStepFactory(
                    kotlinBuckConfig.getKotlinCompiler().get(),
                    args.extraKotlincArguments.get()),
                Optional.<Path>absent(),
                /* manifest file */ Optional.<SourcePath>absent(),
                Optional.<String>absent(),
                ImmutableSortedSet.<BuildTarget>of(),
                /* classesToRemoveFromJar */ ImmutableSet.<Pattern>of()));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(defaultJavaLibrary.getBuildTarget())));

    return defaultJavaLibrary;
  }


  @SuppressFieldNotInitialized
  public static class Arg extends JvmLibraryArg {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;
    public Optional<ImmutableList<String>> extraKotlincArguments;
    public Optional<ImmutableSortedSet<BuildTarget>> providedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> exportedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}

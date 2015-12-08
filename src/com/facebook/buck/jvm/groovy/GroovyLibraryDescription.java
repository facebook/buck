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

package com.facebook.buck.jvm.groovy;

import static com.facebook.buck.jvm.common.ResourceValidator.validateResources;

import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.model.BuildTarget;
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

public class GroovyLibraryDescription implements Description<GroovyLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("groovy_library");

  private final GroovyBuckConfig groovyBuckConfig;

  public GroovyLibraryDescription(
      GroovyBuckConfig groovyBuckConfig) {
    this.groovyBuckConfig = groovyBuckConfig;
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
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    BuildTarget abiJarTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(CalculateAbi.FLAVOR)
            .build();

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
                /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
                new GroovycStepFactory(
                    groovyBuckConfig.getGroovyCompiler().get(),
                    args.extraArguments),
                Optional.<Path>absent(),
                Optional.<String>absent(),
                ImmutableSortedSet.<BuildTarget>of()));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(defaultJavaLibrary.getBuildTarget())));

    return defaultJavaLibrary;
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;
    public Optional<ImmutableList<String>> extraArguments;
    public Optional<ImmutableSortedSet<BuildTarget>> providedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> exportedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

}

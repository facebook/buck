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

package com.facebook.buck.thrift;

import com.facebook.buck.java.DefaultJavaLibrary;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.Javac;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.nio.file.Path;

public class ThriftJavaEnhancer implements ThriftLanguageSpecificEnhancer {

  private static final Flavor JAVA_FLAVOR = new Flavor("java");
  private static final BuildRuleType SOURCE_ZIP_TYPE = new BuildRuleType("thrift-java-source-zip");

  private final ThriftBuckConfig thriftBuckConfig;
  private final Javac javac;
  private final JavacOptions templateOptions;

  public ThriftJavaEnhancer(
      ThriftBuckConfig thriftBuckConfig,
      Javac javac,
      JavacOptions templateOptions) {
    this.thriftBuckConfig = thriftBuckConfig;
    this.javac = javac;
    this.templateOptions = templateOptions;
  }

  @Override
  public String getLanguage() {
    return "java";
  }

  @Override
  public Flavor getFlavor() {
    return JAVA_FLAVOR;
  }

  @VisibleForTesting
  protected BuildTarget getSourceZipBuildTarget(BuildTarget target, String name) {
    return BuildTargets.createFlavoredBuildTarget(
        target.getUnflavoredTarget(),
        new Flavor(
            String.format(
                "thrift-java-source-zip-%s",
                name.replace('/', '-').replace('.', '-').replace('+', '-').replace(' ', '-'))));
  }

  private Path getSourceZipOutputPath(BuildTarget target, String name) {
    BuildTarget flavoredTarget = getSourceZipBuildTarget(target, name);
    return BuildTargets.getBinPath(flavoredTarget, "%s" + Javac.SRC_ZIP);
  }

  @Override
  public DefaultJavaLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ThriftConstructorArg args,
      ImmutableMap<String, ThriftSource> sources,
      ImmutableSortedSet<BuildRule> deps) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // Pack all the generated sources into a single source zip that we'll pass to the
    // java rule below.
    ImmutableSortedSet.Builder<BuildRule> sourceZipsBuilder = ImmutableSortedSet.naturalOrder();
    for (ImmutableMap.Entry<String, ThriftSource> ent : sources.entrySet()) {
      String name = ent.getKey();
      BuildRule compilerRule = ent.getValue().getCompileRule();
      Path sourceDirectory = ent.getValue().getOutputDir().resolve("gen-java");

      BuildTarget sourceZipTarget = getSourceZipBuildTarget(params.getBuildTarget(), name);
      Path sourceZip = getSourceZipOutputPath(params.getBuildTarget(), name);

      sourceZipsBuilder.add(
          new SrcZip(
              params.copyWithChanges(
                  SOURCE_ZIP_TYPE,
                  sourceZipTarget,
                  ImmutableSortedSet.of(compilerRule),
                  ImmutableSortedSet.<BuildRule>of()),
              pathResolver,
              sourceZip,
              sourceDirectory));
    }
    ImmutableSortedSet<BuildRule> sourceZips = sourceZipsBuilder.build();
    resolver.addAllToIndex(sourceZips);

    // Create to main compile rule.
    BuildRuleParams javaParams = params.copyWithChanges(
        JavaLibraryDescription.TYPE,
        BuildTargets.createFlavoredBuildTarget(
            params.getBuildTarget().getUnflavoredTarget(),
            getFlavor()),
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(sourceZips)
            .addAll(deps)
            .build(),
        ImmutableSortedSet.<BuildRule>of());
    return new DefaultJavaLibrary(
        javaParams,
        pathResolver,
        FluentIterable.from(sourceZips)
            .transform(SourcePaths.TO_BUILD_TARGET_SOURCE_PATH)
            .toSortedSet(Ordering.natural()),
        /* resources */ ImmutableSet.<SourcePath>of(),
        /* proguardConfig */ Optional.<Path>absent(),
        /* postprocessClassesCommands */ ImmutableList.<String>of(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        javac,
        templateOptions,
        /* resourcesRoot */ Optional.<Path>absent());
  }

  private ImmutableSet<BuildTarget> getImplicitDeps() {
    return ImmutableSet.of(thriftBuckConfig.getJavaDep());
  }

  @Override
  public ImmutableSet<BuildTarget> getImplicitDepsForTargetFromConstructorArg(
      BuildTarget target,
      ThriftConstructorArg args) {
    return getImplicitDeps();
  }

  @Override
  public ImmutableSet<String> getOptions(BuildTarget target, ThriftConstructorArg arg) {
    return arg.javaOptions.or(ImmutableSet.<String>of());
  }

}

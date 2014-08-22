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
import com.facebook.buck.java.JavaBuckConfig;
import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class JavaThriftLibraryDescription
  implements Description<JavaThriftLibraryDescription.Arg>, Flavored,
    ImplicitDepsInferringDescription {
  public static final BuildRuleType TYPE = new BuildRuleType("thrift_library");
  private static final Flavor JAVA_FLAVOR = new Flavor("java");
  private static final Flavor JAVASRCS_FLAVOR = new Flavor("java_srcs");

  @VisibleForTesting
  private final JavaCompilerEnvironment javacEnv;

  private final JavaBuckConfig javaBuckConfig;

  public JavaThriftLibraryDescription(
      JavaCompilerEnvironment javacEnv,
      JavaBuckConfig javaBuckConfig) {
    this.javacEnv = Preconditions.checkNotNull(javacEnv);
    this.javaBuckConfig = javaBuckConfig;
  }

  @Override
  public <A extends Arg> DefaultJavaLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    Flavor flavor = params.getBuildTarget().getFlavor();
    if (!hasFlavor(flavor)) {
      throw new HumanReadableException("Unrecognized flavor \"%s\"", flavor);
    }

    BuildRuleParams thriftParams = params.copyWithChanges(
        params.getBuildRuleType(),
        BuildTargets.createFlavoredBuildTarget(
            params.getBuildTarget().getUnflavoredTarget(),
            JAVASRCS_FLAVOR),
        params.getDeclaredDeps(),
        params.getExtraDeps());
    JavaThriftLibrary javaThriftLibrary = new JavaThriftLibrary(thriftParams, args.srcs);
    resolver.addToIndex(javaThriftLibrary);
    ImmutableSortedSet.Builder<BuildRule> javaDeps = ImmutableSortedSet.naturalOrder();
    javaDeps.addAll(params.getDeclaredDeps());
    javaDeps.add(javaThriftLibrary);
    javaDeps.addAll(params.getExtraDeps());
    BuildRuleParams javaParams = params.copyWithChanges(
        params.getBuildRuleType(),
        params.getBuildTarget(),
        javaDeps.build(),
        ImmutableSortedSet.<BuildRule>of());
    JavacOptions javacOptions = JavacOptions.builder()
        .setJavaCompilerEnvironment(javacEnv)
        .build();
    DefaultJavaLibrary defaultJavaLibrary = new DefaultJavaLibrary(
        javaParams,
        ImmutableSet.of(new BuildRuleSourcePath(javaThriftLibrary)),
        /* resources */ ImmutableSet.<SourcePath>of(),
        /* proguardConfig */ Optional.<Path>absent(),
        /* postprocessClassesCommands */ ImmutableList.<String>of(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        javacOptions,
        /* resourcesRoot */ Optional.<Path>absent());
    return defaultJavaLibrary;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public boolean hasFlavor(Flavor flavor) {
    return JAVA_FLAVOR.equals(flavor) || Flavor.DEFAULT.equals(flavor);
  }

  /**
   * Build against libthrift
   */
  @Override
  public Iterable<String> findDepsFromParams(BuildRuleFactoryParams params) {
    return ImmutableSet.copyOf(javaBuckConfig.getJavaThriftDep().asSet());
  }

  @SuppressFieldNotInitialized
  public static class Arg implements ConstructorArg {
    public String name;
    public ImmutableSortedSet<SourcePath> srcs;
    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }

}

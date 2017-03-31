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

import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryRules;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class AndroidBuildConfigDescription
    implements Description<AndroidBuildConfigDescription.Arg> {

  private static final Flavor GEN_JAVA_FLAVOR = InternalFlavor.of("gen_java_android_build_config");
  private final JavaBuckConfig javaBuckConfig;
  private final JavacOptions androidJavacOptions;

  public AndroidBuildConfigDescription(
      JavaBuckConfig javaBuckConfig,
      JavacOptions androidJavacOptions) {
    this.javaBuckConfig = javaBuckConfig;
    this.androidJavacOptions = androidJavacOptions;
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
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    if (CalculateAbi.isAbiTarget(params.getBuildTarget())) {
      BuildTarget configTarget = CalculateAbi.getLibraryTarget(params.getBuildTarget());
      BuildRule configRule = resolver.requireRule(configTarget);
      return CalculateAbi.of(
          params.getBuildTarget(),
          ruleFinder,
          params,
          Preconditions.checkNotNull(configRule.getSourcePathToOutput()));
    }

    return createBuildRule(
        params,
        args.javaPackage,
        args.values,
        args.valuesFile,
        /* useConstantExpressions */ false,
        JavacFactory.create(ruleFinder, javaBuckConfig, null),
        androidJavacOptions,
        javaBuckConfig.shouldSuggestDependencies(),
        resolver);
  }

  /**
   * @param values Collection whose entries identify fields for the generated
   *     {@code BuildConfig} class. The values for fields can be overridden by values from the
   *     {@code valuesFile} file, if present.
   * @param valuesFile Path to a file with values to override those in {@code values}.
   * @param ruleResolver Any intermediate rules introduced by this method will be added to this
   *     {@link BuildRuleResolver}.
   */
  static AndroidBuildConfigJavaLibrary createBuildRule(
      BuildRuleParams params,
      String javaPackage,
      BuildConfigFields values,
      Optional<SourcePath> valuesFile,
      boolean useConstantExpressions,
      Javac javac,
      JavacOptions javacOptions,
      boolean suggestDependencies,
      BuildRuleResolver ruleResolver) throws NoSuchBuildTargetException {
    // Normally, the build target for an intermediate rule is a flavored version of the target for
    // the original rule. For example, if the build target for an android_build_config() were
    // //foo:bar, then the build target for the intermediate AndroidBuildConfig rule created by this
    // method would be //foo:bar#gen_java_android_build_config.
    //
    // However, in the case of an android_binary() with multiple android_build_config() rules in its
    // transitive deps, it must create one intermediate AndroidBuildConfigJavaLibrary for each
    // android_build_config() dependency. The primary difference is that in each of the new versions
    // of the AndroidBuildConfigJavaLibrary, constant expressions will be used so the values can be
    // inlined (whereas non-constant-expressions were used in the original versions). Because there
    // are multiple intermediate rules based on the same android_binary(), the flavor cannot just be
    // #gen_java_android_build_config because that would lead to build target collisions, so the
    // flavor must be parameterized by the java package to ensure it is unique.
    //
    // This fixes the issue, but deviates from the common pattern where a build rule has at most
    // one flavored version of itself for a given flavor.
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    BuildTarget buildConfigBuildTarget;
    if (!params.getBuildTarget().isFlavored()) {
      // android_build_config() case.
      Preconditions.checkArgument(!useConstantExpressions);
      buildConfigBuildTarget = params.getBuildTarget().withFlavors(GEN_JAVA_FLAVOR);
    } else {
      // android_binary() graph enhancement case.
      Preconditions.checkArgument(useConstantExpressions);
      buildConfigBuildTarget = params.getBuildTarget().withFlavors(
          InternalFlavor.of(GEN_JAVA_FLAVOR.getName() + '_' + javaPackage.replace('.', '_')));
    }

    // Create one build rule to generate BuildConfig.java.
    BuildRuleParams buildConfigParams = params
        .withBuildTarget(buildConfigBuildTarget)
        .copyAppendingExtraDeps(ruleFinder.filterBuildRuleInputs(OptionalCompat.asSet(valuesFile)));
    AndroidBuildConfig androidBuildConfig = new AndroidBuildConfig(
        buildConfigParams,
        javaPackage,
        values,
        valuesFile,
        useConstantExpressions);
    ruleResolver.addToIndex(androidBuildConfig);

    // Create a second build rule to compile BuildConfig.java and expose it as a JavaLibrary.
    BuildRuleParams javaLibraryParams = params
        .copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(androidBuildConfig)),
            Suppliers.ofInstance(ImmutableSortedSet.of()));
    return new AndroidBuildConfigJavaLibrary(
        javaLibraryParams,
        pathResolver,
        ruleFinder,
        javac,
        javacOptions,
        suggestDependencies,
        JavaLibraryRules.getAbiSourcePaths(ruleResolver, javaLibraryParams.getBuildDeps()),
        androidBuildConfig);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    @Hint(name = "package")
    public String javaPackage;

    /** This will never be absent after this Arg is populated. */
    public BuildConfigFields values = BuildConfigFields.empty();

    /** If present, contents of file can override those of {@link #values}. */
    public Optional<SourcePath> valuesFile;
  }
}

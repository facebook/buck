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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedSet;

public class AndroidBuildConfigDescription
    implements Description<AndroidBuildConfigDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("android_build_config");

  private static final BuildRuleType GEN_JAVA_TYPE = BuildRuleType.of(
      "gen_java_android_build_config");
  private static final Flavor GEN_JAVA_FLAVOR = ImmutableFlavor.of(GEN_JAVA_TYPE.getName());
  private final JavacOptions androidJavacOptions;

  public AndroidBuildConfigDescription(JavacOptions androidJavacOptions) {
    this.androidJavacOptions = androidJavacOptions;
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
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return createBuildRule(
        params,
        args.javaPackage,
        args.values.get(),
        args.valuesFile,
        /* useConstantExpressions */ false,
        androidJavacOptions,
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
      JavacOptions javacOptions,
      BuildRuleResolver ruleResolver) {
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
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildTarget buildConfigBuildTarget;
    if (!params.getBuildTarget().isFlavored()) {
      // android_build_config() case.
      Preconditions.checkArgument(!useConstantExpressions);
      buildConfigBuildTarget =
          BuildTarget.builder(params.getBuildTarget().getUnflavoredBuildTarget())
          .addFlavors(GEN_JAVA_FLAVOR)
          .build();
    } else {
      // android_binary() graph enhancement case.
      Preconditions.checkArgument(useConstantExpressions);
      buildConfigBuildTarget =
          BuildTarget.builder(params.getBuildTarget().getUnflavoredBuildTarget())
          .addFlavors(
              ImmutableFlavor.of(GEN_JAVA_FLAVOR.getName() + '_' + javaPackage.replace('.', '_')))
          .build();
    }

    // Create one build rule to generate BuildConfig.java.
    BuildRuleParams buildConfigParams = params.copyWithChanges(
        GEN_JAVA_TYPE,
        buildConfigBuildTarget,
        Suppliers.ofInstance(params.getDeclaredDeps()),
        /* extraDeps */ Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(params.getExtraDeps())
                .addAll(pathResolver.filterBuildRuleInputs(valuesFile.asSet()))
                .build()));
    AndroidBuildConfig androidBuildConfig = new AndroidBuildConfig(
        buildConfigParams,
        pathResolver,
        javaPackage,
        values,
        valuesFile,
        useConstantExpressions);
    ruleResolver.addToIndex(androidBuildConfig);

    // Create a second build rule to compile BuildConfig.java and expose it as a JavaLibrary.
    BuildRuleParams javaLibraryParams = params.copyWithChanges(
        TYPE,
        params.getBuildTarget(),
        /* declaredDeps */ Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>of(androidBuildConfig)),
        /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
    return new AndroidBuildConfigJavaLibrary(
        javaLibraryParams,
        pathResolver,
        javacOptions,
        androidBuildConfig);
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    @Hint(name = "package")
    public String javaPackage;

    /** This will never be absent after this Arg is populated. */
    public Optional<BuildConfigFields> values;

    /** If present, contents of file can override those of {@link #values}. */
    public Optional<SourcePath> valuesFile;
  }
}

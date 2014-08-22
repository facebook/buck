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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

public class AndroidBuildConfigDescription
    implements Description<AndroidBuildConfigDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("android_build_config");

  private static final BuildRuleType GEN_JAVA_TYPE = new BuildRuleType(
      "gen_java_android_build_config");
  private static final Flavor GEN_JAVA_FLAVOR = new Flavor(GEN_JAVA_TYPE.getName());

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
        /* useConstantExpressions */ false);
  }

  /**
   * @param values Collection whose entries identify fields for the generated
   *     {@code BuildConfig} class. The values for fields can be overridden by values from the
   *     {@code valuesFile} file, if present.
   * @param valuesFile Path to a file with values to override those in {@code values}.
   */
  static AndroidBuildConfigJavaLibrary createBuildRule(
      BuildRuleParams params,
      String javaPackage,
      BuildConfigFields values,
      Optional<SourcePath> valuesFile,
      boolean useConstantExpressions) {
    // Create one build rule to generate BuildConfig.java.
    BuildRuleParams buildConfigParams = params.copyWithChanges(
        GEN_JAVA_TYPE,
        BuildTarget.builder(params.getBuildTarget()).setFlavor(GEN_JAVA_FLAVOR).build(),
        params.getDeclaredDeps(),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(params.getExtraDeps())
            .addAll(SourcePaths.filterBuildRuleInputs(valuesFile.asSet()))
            .build());
    AndroidBuildConfig androidBuildConfig = new AndroidBuildConfig(
        buildConfigParams,
        javaPackage,
        values,
        valuesFile,
        useConstantExpressions);

    // Create a second build rule to compile BuildConfig.java and expose it as a JavaLibrary.
    BuildRuleParams javaLibraryParams = params.copyWithChanges(
        TYPE,
        params.getBuildTarget(),
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(androidBuildConfig),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
    return new AndroidBuildConfigJavaLibrary(
        javaLibraryParams,
        androidBuildConfig);
  }

  @SuppressFieldNotInitialized
  public static class Arg implements ConstructorArg {
    @Hint(name = "package")
    public String javaPackage;

    /** This will never be absent after this Arg is populated. */
    public Optional<BuildConfigFields> values;

    /** If present, contents of file can override those of {@link #values}. */
    public Optional<SourcePath> valuesFile;
  }
}

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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Map;

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
    // TODO(mbolin): Make it possible for users to specify additional constants via args.
    Map<String, Object> constants = ImmutableMap.<String, Object>of();

    return createBuildRule(params, args.javaPackage, /* useConstantExpressions */ false, constants);
  }

  static AndroidBuildConfigJavaLibrary createBuildRule(
      BuildRuleParams params,
      String javaPackage,
      boolean useConstantExpressions,
      Map<String, Object> constants) {
    // Create one build rule to generate BuildConfig.java.
    BuildRuleParams buildConfigParams = params.copyWithChanges(
        GEN_JAVA_TYPE,
        BuildTarget.builder(params.getBuildTarget()).setFlavor(GEN_JAVA_FLAVOR).build(),
        params.getDeclaredDeps(),
        params.getExtraDeps());
    AndroidBuildConfig androidBuildConfig = new AndroidBuildConfig(
        buildConfigParams,
        javaPackage,
        useConstantExpressions,
        constants);

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

  public static class Arg implements ConstructorArg {
    @Hint(name = "package")
    public String javaPackage;
  }
}

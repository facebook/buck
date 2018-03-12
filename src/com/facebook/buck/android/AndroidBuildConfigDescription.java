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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.java.CalculateClassAbi;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryRules;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class AndroidBuildConfigDescription
    implements Description<AndroidBuildConfigDescriptionArg> {

  private static final Flavor GEN_JAVA_FLAVOR = InternalFlavor.of("gen_java_android_build_config");

  private final JavaBuckConfig javaBuckConfig;

  public AndroidBuildConfigDescription(JavaBuckConfig javaBuckConfig) {
    this.javaBuckConfig = javaBuckConfig;
  }

  @Override
  public Class<AndroidBuildConfigDescriptionArg> getConstructorArgType() {
    return AndroidBuildConfigDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidBuildConfigDescriptionArg args) {
    BuildRuleResolver resolver = context.getBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    if (HasJavaAbi.isClassAbiTarget(buildTarget)) {
      BuildTarget configTarget = HasJavaAbi.getLibraryTarget(buildTarget);
      BuildRule configRule = resolver.requireRule(configTarget);
      return CalculateClassAbi.of(
          buildTarget,
          ruleFinder,
          context.getProjectFilesystem(),
          params,
          Preconditions.checkNotNull(configRule.getSourcePathToOutput()));
    }

    return createBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        args.getPackage(),
        args.getValues(),
        args.getValuesFile(),
        /* useConstantExpressions */ false,
        JavacFactory.create(ruleFinder, javaBuckConfig, null),
        context
            .getToolchainProvider()
            .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
            .getJavacOptions(),
        resolver);
  }

  /**
   * @param values Collection whose entries identify fields for the generated {@code BuildConfig}
   *     class. The values for fields can be overridden by values from the {@code valuesFile} file,
   *     if present.
   * @param valuesFile Path to a file with values to override those in {@code values}.
   * @param ruleResolver Any intermediate rules introduced by this method will be added to this
   *     {@link BuildRuleResolver}.
   */
  static AndroidBuildConfigJavaLibrary createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      String javaPackage,
      BuildConfigFields values,
      Optional<SourcePath> valuesFile,
      boolean useConstantExpressions,
      Javac javac,
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
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget buildConfigBuildTarget;
    if (!buildTarget.isFlavored()) {
      // android_build_config() case.
      Preconditions.checkArgument(!useConstantExpressions);
      buildConfigBuildTarget = buildTarget.withFlavors(GEN_JAVA_FLAVOR);
    } else {
      // android_binary() graph enhancement case.
      Preconditions.checkArgument(useConstantExpressions);
      buildConfigBuildTarget =
          buildTarget.withFlavors(
              InternalFlavor.of(GEN_JAVA_FLAVOR.getName() + '_' + javaPackage.replace('.', '_')));
    }

    // Create one build rule to generate BuildConfig.java.
    BuildRuleParams buildConfigParams = params;
    Optional<BuildRule> valuesFileRule = valuesFile.flatMap(ruleFinder::getRule);
    if (valuesFileRule.isPresent()) {
      buildConfigParams = buildConfigParams.copyAppendingExtraDeps(valuesFileRule.get());
    }
    AndroidBuildConfig androidBuildConfig =
        new AndroidBuildConfig(
            buildConfigBuildTarget,
            projectFilesystem,
            buildConfigParams,
            javaPackage,
            values,
            valuesFile,
            useConstantExpressions);
    ruleResolver.addToIndex(androidBuildConfig);

    // Create a second build rule to compile BuildConfig.java and expose it as a JavaLibrary.
    BuildRuleParams javaLibraryParams =
        params.withDeclaredDeps(ImmutableSortedSet.of(androidBuildConfig)).withoutExtraDeps();
    return new AndroidBuildConfigJavaLibrary(
        buildTarget,
        projectFilesystem,
        javaLibraryParams,
        pathResolver,
        ruleFinder,
        javac,
        javacOptions,
        JavaLibraryRules.getAbiClasspath(ruleResolver, javaLibraryParams.getBuildDeps()),
        androidBuildConfig);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAndroidBuildConfigDescriptionArg extends CommonDescriptionArg {
    /** For R.java */
    String getPackage();

    @Value.Default
    default BuildConfigFields getValues() {
      return BuildConfigFields.of();
    }

    /** If present, contents of file can override those of {@link #getValues}. */
    Optional<SourcePath> getValuesFile();
  }
}

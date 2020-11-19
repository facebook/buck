/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.nullsafe.NullsafeConfig;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

/**
 * Nullsafe build rule augments {@link com.facebook.buck.jvm.core.JavaLibrary} rule by enabling and
 * configuring Nullsafe javac plugin.
 */
public class Nullsafe extends ModernBuildRule<Nullsafe.Impl> {

  public static final Flavor NULLSAFEX = InternalFlavor.of("nullsafex");

  public static boolean hasSupportedFlavor(FlavorSet flavors) {
    return flavors.contains(NULLSAFEX);
  }

  protected Nullsafe(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      Impl buildable) {
    super(buildTarget, filesystem, ruleFinder, buildable);
  }

  /**
   * Return new {@link JavacOptions} built from provided {@code javacOptions} but with added options
   * required to properly setup Nullsafe javac plugin for provided {@code buildTarget}.
   */
  public static JavacOptions augmentJavacOptions(
      JavacOptions javacOptions,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      NullsafeConfig nullsafeConfig) {
    BuildTarget nullsafePluginTarget =
        nullsafeConfig.requirePlugin(buildTarget.getTargetConfiguration());

    JavacPlugin nullsafePlugin = (JavacPlugin) graphBuilder.requireRule(nullsafePluginTarget);
    JavacPluginProperties.Builder nullsafePluginPropsBuilder =
        JavacPluginProperties.builder(nullsafePlugin.getUnresolvedProperties());
    nullsafeConfig
        .getSignatures(buildTarget.getTargetConfiguration())
        .ifPresent(
            sigs -> nullsafePluginPropsBuilder.putSourcePathParams("nullsafe.signatures", sigs));

    ResolvedJavacPluginProperties resolvedNullsafePluginProperties =
        new ResolvedJavacPluginProperties(
            nullsafePluginPropsBuilder.build(),
            graphBuilder.getSourcePathResolver(),
            projectFilesystem.getRootPath());

    JavacPluginParams augmentedPluginParams =
        JavacPluginParams.builder()
            .from(javacOptions.getStandardJavacPluginParams())
            .addPluginProperties(resolvedNullsafePluginProperties)
            .build(graphBuilder.getSourcePathResolver(), projectFilesystem.getRootPath());

    return JavacOptions.builder(javacOptions)
        .setStandardJavacPluginParams(augmentedPluginParams)
        .addExtraArguments("-XDcompilePolicy=byfile")
        .build();
  }

  /** Helper method to add parse-time deps to target graph for nullsafex flavored targets. */
  public static void addParseTimeDeps(
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder,
      BuildTarget buildTarget,
      NullsafeConfig nullsafeConfig) {
    if (buildTarget.getFlavors().contains(NULLSAFEX)) {
      nullsafeConfig
          .getPlugin(buildTarget.getTargetConfiguration())
          .ifPresent(targetGraphOnlyDepsBuilder::add);
      nullsafeConfig
          .getSignatures(buildTarget.getTargetConfiguration())
          .filter(BuildTargetSourcePath.class::isInstance)
          .map(sourcePath -> ((BuildTargetSourcePath) sourcePath).getTarget())
          .ifPresent(targetGraphOnlyDepsBuilder::add);
    }
  }

  /**
   * Create Nullsafe build rule based off of augmented JavaLibrary (AndroidLibrary is a subclass of
   * JavaLibrary so included too).
   *
   * @param graphBuilder aka rule finder
   * @param augmentedJavaLibrary JavaLibrary built with javacOptions augmented by {@link
   *     #augmentJavacOptions}
   * @return Nullsafe build rule
   */
  public static Nullsafe create(
      ActionGraphBuilder graphBuilder, DefaultJavaLibrary augmentedJavaLibrary) {
    Impl javaLibraryBuildable = new Impl(augmentedJavaLibrary.getBuildable());
    return new Nullsafe(
        augmentedJavaLibrary.getBuildTarget(),
        augmentedJavaLibrary.getProjectFilesystem(),
        graphBuilder,
        javaLibraryBuildable);
  }

  /**
   * A buildable that wraps underlying {@link DefaultJavaLibraryBuildable}.
   *
   * <p>We wrap the underlying library's buildable in order to support 2 cases:
   *
   * <ul>
   *   <p>
   *   <li>Forwarding. Here the rule executes the same build steps the underlying library would
   *       execute, but with Nullsafe javac plugin enabled, so Nullsafe errors become build errors.
   *   <li>(NOT IMPLEMENTED YET) Saving results to a json-file. In this case, the rule executes the
   *       same steps as the underlying library, but nullsafe issues are not reported to javac, but
   *       are rather written into a json file. This mode is required for compatibility with {@link
   *       com.facebook.buck.infer.InferJava} and existing tooling.
   * </ul>
   */
  public static class Impl implements Buildable {
    @AddToRuleKey private final DefaultJavaLibraryBuildable underlyingLibrary;

    public Impl(DefaultJavaLibraryBuildable underlyingLibrary) {
      this.underlyingLibrary = underlyingLibrary;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return underlyingLibrary.getBuildSteps(
          buildContext, filesystem, outputPathResolver, buildCellPathFactory);
    }
  }
}

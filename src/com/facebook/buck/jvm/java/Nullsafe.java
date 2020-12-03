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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.nullsafe.NullsafeConfig;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import javax.annotation.Nullable;

/**
 * Nullsafe build rule augments {@link com.facebook.buck.jvm.core.JavaLibrary} rule by enabling and
 * configuring Nullsafe javac plugin.
 *
 * <p>More precisely the integration happens in 2 steps:
 *
 * <ul>
 *   <li>Change {@link JavacOptions} to enable * Nullsafe plugin during compilation.
 *   <li>Opaquely wrap {@link DefaultJavaLibrary}. Take {@link DefaultJavaLibraryBuildable}, reuse *
 *       its steps for running the compilation (and possibly add additional pre-/post-processing
 *       steps).
 * </ul>
 *
 * The reason for doing it this ways is to make the integration as non-invasive and isolate as
 * possible to not further complicate Java build rules.
 */
public class Nullsafe extends ModernBuildRule<Nullsafe.Impl> {

  public static final Flavor NULLSAFEX = InternalFlavor.of("nullsafex");
  public static final Flavor NULLSAFEX_JSON = InternalFlavor.of("nullsafex-json");

  public static final String REPORTS_JSON_DIR = "reports";

  /** Check that flavors in the set are nullsafe flavors only. */
  public static boolean hasSupportedFlavor(ImmutableSet<Flavor> flavors) {
    return !flavors.isEmpty()
        && flavors.stream()
            .allMatch(flavor -> flavor.equals(NULLSAFEX) || flavor.equals(NULLSAFEX_JSON));
  }

  /** See {@link #hasSupportedFlavor(ImmutableSet)} */
  public static boolean hasSupportedFlavor(FlavorSet flavors) {
    return hasSupportedFlavor(flavors.getSet());
  }

  /**
   * Check that the flavored target outputs JSON report, rather then reports issues as javac
   * compilation errors.
   */
  public static boolean shouldReportJson(BuildTarget buildTarget) {
    return buildTarget.getFlavors().contains(NULLSAFEX_JSON);
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

    ImmutableJavacPluginPathParams.Builder pathParams =
        ImmutableJavacPluginPathParams.builder()
            .from(nullsafePlugin.getUnresolvedProperties().getPathParams());

    nullsafeConfig
        .getSignatures(buildTarget.getTargetConfiguration())
        .ifPresent(sigs -> pathParams.putSourcePathParams("nullsafe.signatures", sigs));

    if (shouldReportJson(buildTarget)) {
      RelPath genPath = BuildPaths.getGenDir(projectFilesystem.getBuckPaths(), buildTarget);
      pathParams.putRelPathParams(
          "nullsafe.writeJsonReportToDir", genPath.resolveRel(REPORTS_JSON_DIR));
    }

    nullsafePluginPropsBuilder.setPathParams(pathParams.build());

    ResolvedJavacPluginProperties resolvedNullsafePluginProperties =
        ResolvedJavacPluginProperties.of(
            nullsafePluginPropsBuilder.build(),
            graphBuilder.getSourcePathResolver(),
            projectFilesystem.getRootPath());

    JavacPluginParams augmentedPluginParams =
        JavacPluginParams.builder()
            .from(javacOptions.getStandardJavacPluginParams())
            .addPluginProperties(resolvedNullsafePluginProperties)
            .build(graphBuilder.getSourcePathResolver(), projectFilesystem.getRootPath());

    JavacOptions.Builder javacOptionsBuilder =
        JavacOptions.builder(javacOptions)
            .setStandardJavacPluginParams(augmentedPluginParams)
            .addExtraArguments("-XDcompilePolicy=byfile");

    if (shouldReportJson(buildTarget)) {
      javacOptionsBuilder.addExtraArguments("-Anullsafe.reportToJava=false");
    }

    return javacOptionsBuilder.build();
  }

  /** Helper method to add parse-time deps to target graph for nullsafex flavored targets. */
  public static void addParseTimeDeps(
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder,
      BuildTarget buildTarget,
      NullsafeConfig nullsafeConfig) {
    if (hasSupportedFlavor(buildTarget.getFlavors())) {
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
    BuildTarget buildTarget = augmentedJavaLibrary.getBuildTarget();

    Impl javaLibraryBuildable =
        new Impl(augmentedJavaLibrary.getBuildable(), shouldReportJson(buildTarget));

    return new Nullsafe(
        augmentedJavaLibrary.getBuildTarget(),
        augmentedJavaLibrary.getProjectFilesystem(),
        graphBuilder,
        javaLibraryBuildable);
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    OutputPath output = getBuildable().output;
    if (output != null) {
      return getSourcePath(output);
    }

    return null;
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
    @AddToRuleKey private final boolean reportJson;
    @AddToRuleKey @Nullable private final OutputPath output;

    public Impl(DefaultJavaLibraryBuildable underlyingLibrary, boolean reportJson) {
      this.underlyingLibrary = underlyingLibrary;
      this.reportJson = reportJson;

      if (this.reportJson) {
        output = new OutputPath(REPORTS_JSON_DIR);
      } else {
        output = null;
      }
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      ImmutableList<Step> compilationSteps =
          underlyingLibrary.getBuildSteps(
              buildContext, filesystem, outputPathResolver, buildCellPathFactory);

      if (reportJson) {
        Preconditions.checkNotNull(output, "output should be non-null for JSON reporting");
        RelPath reportDir = outputPathResolver.resolvePath(output);

        ImmutableList.Builder<Step> steps = ImmutableList.builder();
        steps.addAll(MakeCleanDirectoryIsolatedStep.of(reportDir));
        steps.addAll(compilationSteps);
        return steps.build();
      } else {
        return compilationSteps;
      }
    }
  }
}

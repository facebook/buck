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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

public class PrebuiltJarDescription implements Description<PrebuiltJarDescriptionArg> {

  @Override
  public Class<PrebuiltJarDescriptionArg> getConstructorArgType() {
    return PrebuiltJarDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      PrebuiltJarDescriptionArg args) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    if (HasJavaAbi.isClassAbiTarget(buildTarget)) {
      return CalculateClassAbi.of(
          buildTarget, ruleFinder, projectFilesystem, params, args.getBinaryJar());
    }

    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    BuildRule prebuilt =
        new PrebuiltJar(
            buildTarget,
            projectFilesystem,
            params,
            pathResolver,
            args.getBinaryJar(),
            args.getSourceJar(),
            args.getGwtJar(),
            args.getJavadocUrl(),
            args.getMavenCoords(),
            args.getProvided(),
            args.getRequiredForSourceOnlyAbi());

    buildTarget.checkUnflavored();
    BuildTarget gwtTarget = buildTarget.withAppendedFlavors(JavaLibrary.GWT_MODULE_FLAVOR);
    BuildRuleParams gwtParams =
        params.withDeclaredDeps(ImmutableSortedSet.of(prebuilt)).withoutExtraDeps();
    BuildRule gwtModule = createGwtModule(gwtTarget, projectFilesystem, gwtParams, args);
    resolver.addToIndex(gwtModule);

    return prebuilt;
  }

  @VisibleForTesting
  static BuildRule createGwtModule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      PrebuiltJarDescriptionArg arg) {
    // Because a PrebuiltJar rarely requires any building whatsoever (it could if the source_jar
    // is a BuildTargetSourcePath), we make the PrebuiltJar a dependency of the GWT module. If this
    // becomes a performance issue in practice, then we will explore reducing the dependencies of
    // the GWT module.
    final SourcePath input;
    if (arg.getGwtJar().isPresent()) {
      input = arg.getGwtJar().get();
    } else if (arg.getSourceJar().isPresent()) {
      input = arg.getSourceJar().get();
    } else {
      input = arg.getBinaryJar();
    }

    class ExistingOuputs extends AbstractBuildRuleWithDeclaredAndExtraDeps {
      @AddToRuleKey private final SourcePath source;
      private final Path output;

      protected ExistingOuputs(
          BuildTarget buildTarget,
          ProjectFilesystem projectFilesystem,
          BuildRuleParams params,
          SourcePath source) {
        super(buildTarget, projectFilesystem, params);
        this.source = source;
        this.output =
            BuildTargets.getGenPath(
                getProjectFilesystem(),
                buildTarget,
                String.format("%s/%%s-gwt.jar", buildTarget.getShortName()));
      }

      @Override
      public ImmutableList<Step> getBuildSteps(
          BuildContext context, BuildableContext buildableContext) {
        buildableContext.recordArtifact(
            context.getSourcePathResolver().getRelativePath(getSourcePathToOutput()));

        ImmutableList.Builder<Step> steps = ImmutableList.builder();

        steps.addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));
        steps.add(
            CopyStep.forFile(
                getProjectFilesystem(),
                context.getSourcePathResolver().getAbsolutePath(source),
                output));

        return steps.build();
      }

      @Override
      public SourcePath getSourcePathToOutput() {
        return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
      }
    }
    return new ExistingOuputs(buildTarget, projectFilesystem, params, input);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPrebuiltJarDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, MaybeRequiredForSourceOnlyAbiArg {
    SourcePath getBinaryJar();

    Optional<SourcePath> getSourceJar();

    Optional<SourcePath> getGwtJar();

    Optional<String> getJavadocUrl();

    Optional<String> getMavenCoords();

    @Value.Default
    default boolean getProvided() {
      return false;
    }

    @Override
    @Value.Default
    default boolean getRequiredForSourceOnlyAbi() {
      // Prebuilt jars are quick to build, and often contain third-party code, which in turn is
      // often a source of annotations and constants. To ease migration to ABI generation from
      // source without deps, we have them present during ABI gen by default.
      return true;
    }
  }
}

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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

public class PrebuiltJarDescription
    implements DescriptionWithTargetGraph<PrebuiltJarDescriptionArg>,
        VersionPropagator<PrebuiltJarDescriptionArg> {

  @Override
  public Class<PrebuiltJarDescriptionArg> getConstructorArgType() {
    return PrebuiltJarDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PrebuiltJarDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (JavaAbis.isClassAbiTarget(buildTarget)) {
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

    BuildTarget gwtTarget = buildTarget.withAppendedFlavors(JavaLibrary.GWT_MODULE_FLAVOR);
    BuildRuleParams gwtParams =
        params.withDeclaredDeps(ImmutableSortedSet.of(prebuilt)).withoutExtraDeps();
    BuildRule gwtModule = createGwtModule(gwtTarget, projectFilesystem, gwtParams, args);
    graphBuilder.addToIndex(gwtModule);

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
    SourcePath input;
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
            BuildTargetPaths.getGenPath(
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

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
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

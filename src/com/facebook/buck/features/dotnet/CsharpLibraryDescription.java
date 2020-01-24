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

package com.facebook.buck.features.dotnet;

import com.facebook.buck.core.artifact.BuildArtifact;
import com.facebook.buck.core.artifact.BuildTargetSourcePathToArtifactConverter;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.LegacyProviderCompatibleDescription;
import com.facebook.buck.core.rules.ProviderCreationContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.nio.file.Path;
import org.immutables.value.Value;

public class CsharpLibraryDescription
    implements DescriptionWithTargetGraph<CsharpLibraryDescriptionArg>,
        LegacyProviderCompatibleDescription<CsharpLibraryDescriptionArg> {

  public CsharpLibraryDescription() {}

  @Override
  public Class<CsharpLibraryDescriptionArg> getConstructorArgType() {
    return CsharpLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CsharpLibraryDescriptionArg args) {

    BuildRuleResolver resolver = context.getActionGraphBuilder();
    ImmutableList.Builder<Either<BuildRule, String>> refsAsRules = ImmutableList.builder();
    for (Either<BuildTarget, String> ref : args.getDeps()) {
      if (ref.isLeft()) {
        refsAsRules.add(Either.ofLeft(resolver.getRule(ref.getLeft())));
      } else {
        refsAsRules.add(Either.ofRight(ref.getRight()));
      }
    }

    return new CsharpLibrary(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        context
            .getToolchainProvider()
            .getByName(
                DotnetToolchain.DEFAULT_NAME,
                buildTarget.getTargetConfiguration(),
                DotnetToolchain.class)
            .getCsharpCompiler()
            .resolve(context.getActionGraphBuilder(), buildTarget.getTargetConfiguration()),
        args.getSrcs(),
        refsAsRules.build(),
        args.getResources(),
        args.getFrameworkVer(),
        args.getCompilerFlags(),
        getOutputPath(context.getProjectFilesystem(), buildTarget, args));
  }

  private Path getOutputPath(
      ProjectFilesystem filesystem, BuildTarget target, AbstractCsharpLibraryDescriptionArg args) {
    return BuildPaths.getGenDir(filesystem, target).resolve(args.getDllName());
  }

  private ExplicitBuildTargetSourcePath getOutputSourcePath(
      ProjectFilesystem filesystem, BuildTarget target, CsharpLibraryDescriptionArg args) {

    return ExplicitBuildTargetSourcePath.of(target, getOutputPath(filesystem, target, args));
  }

  @Override
  public ProviderInfoCollection createProviders(
      ProviderCreationContext context, BuildTarget buildTarget, CsharpLibraryDescriptionArg args) {
    BuildArtifact output =
        BuildTargetSourcePathToArtifactConverter.convert(
            context.getProjectFilesystem(),
            getOutputSourcePath(context.getProjectFilesystem(), buildTarget, args));
    ImmutableDotnetLibraryProviderInfo dotNetProvider =
        new ImmutableDotnetLibraryProviderInfo(output);
    return ProviderInfoCollectionImpl.builder()
        .put(dotNetProvider)
        .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of(output)));
  }

  @RuleArg
  interface AbstractCsharpLibraryDescriptionArg extends BuildRuleArg, HasSrcs {
    FrameworkVersion getFrameworkVer();

    ImmutableMap<String, SourcePath> getResources();

    @Value.Default
    default String getDllName() {
      return getName() + ".dll";
    }

    // We may have system-provided references ("System.Core.dll") or other build targets
    ImmutableList<Either<BuildTarget, String>> getDeps();

    ImmutableList<String> getCompilerFlags();
  }
}

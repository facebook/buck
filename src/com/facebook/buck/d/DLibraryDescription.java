/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.d;

import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import org.immutables.value.Value;

public class DLibraryDescription
    implements Description<DLibraryDescriptionArg>, VersionPropagator<DLibraryDescriptionArg> {

  private final DBuckConfig dBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform cxxPlatform;

  public DLibraryDescription(
      DBuckConfig dBuckConfig, CxxBuckConfig cxxBuckConfig, CxxPlatform cxxPlatform) {
    this.dBuckConfig = dBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public Class<DLibraryDescriptionArg> getConstructorArgType() {
    return DLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CellPathResolver cellRoots,
      DLibraryDescriptionArg args) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    if (buildTarget.getFlavors().contains(DDescriptionUtils.SOURCE_LINK_TREE)) {
      return DDescriptionUtils.createSourceSymlinkTree(
          buildTarget, projectFilesystem, pathResolver, args.getSrcs());
    }

    BuildTarget sourceTreeTarget =
        buildTarget.withAppendedFlavors(DDescriptionUtils.SOURCE_LINK_TREE);
    DIncludes dIncludes =
        DIncludes.builder()
            .setLinkTree(new DefaultBuildTargetSourcePath(sourceTreeTarget))
            .setSources(args.getSrcs().getPaths())
            .build();

    if (buildTarget.getFlavors().contains(CxxDescriptionEnhancer.STATIC_FLAVOR)) {
      buildRuleResolver.requireRule(sourceTreeTarget);
      return createStaticLibraryBuildRule(
          buildTarget,
          projectFilesystem,
          params,
          buildRuleResolver,
          pathResolver,
          ruleFinder,
          cxxPlatform,
          dBuckConfig,
          /* compilerFlags */ ImmutableList.of(),
          args.getSrcs(),
          dIncludes,
          CxxSourceRuleFactory.PicType.PDC);
    }

    return new DLibrary(buildTarget, projectFilesystem, params, buildRuleResolver, dIncludes);
  }

  /** @return a BuildRule that creates a static library. */
  private BuildRule createStaticLibraryBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      DBuckConfig dBuckConfig,
      ImmutableList<String> compilerFlags,
      SourceList sources,
      DIncludes dIncludes,
      CxxSourceRuleFactory.PicType pic) {

    ImmutableList<SourcePath> compiledSources =
        DDescriptionUtils.sourcePathsForCompiledSources(
            buildTarget,
            projectFilesystem,
            params,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            dBuckConfig,
            compilerFlags,
            sources,
            dIncludes);

    // Write a build rule to create the archive for this library.
    BuildTarget staticTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            buildTarget, cxxPlatform.getFlavor(), pic);

    Path staticLibraryPath =
        CxxDescriptionEnhancer.getStaticLibraryPath(
            projectFilesystem,
            buildTarget,
            cxxPlatform.getFlavor(),
            pic,
            cxxPlatform.getStaticLibraryExtension());

    return Archive.from(
        staticTarget,
        projectFilesystem,
        ruleResolver,
        ruleFinder,
        cxxPlatform,
        cxxBuckConfig.getArchiveContents(),
        staticLibraryPath,
        compiledSources,
        /* cacheable */ true);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractDLibraryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    SourceList getSrcs();

    ImmutableList<String> getLinkerFlags();
  }
}

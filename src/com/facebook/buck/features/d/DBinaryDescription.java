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

package com.facebook.buck.features.d;

import static com.facebook.buck.features.d.DDescriptionUtils.SOURCE_LINK_TREE;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

public class DBinaryDescription
    implements DescriptionWithTargetGraph<DBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<DBinaryDescription.AbstractDBinaryDescriptionArg>,
        VersionRoot<DBinaryDescriptionArg> {

  public static final Flavor BINARY_FLAVOR = InternalFlavor.of("binary");

  private final ToolchainProvider toolchainProvider;
  private final DBuckConfig dBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;

  public DBinaryDescription(
      ToolchainProvider toolchainProvider, DBuckConfig dBuckConfig, CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.dBuckConfig = dBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public Class<DBinaryDescriptionArg> getConstructorArgType() {
    return DBinaryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      DBinaryDescriptionArg args) {

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (buildTarget.getFlavors().contains(SOURCE_LINK_TREE)) {
      return DDescriptionUtils.createSourceSymlinkTree(
          buildTarget, projectFilesystem, pathResolver, ruleFinder, args.getSrcs());
    }

    SymlinkTree sourceTree =
        (SymlinkTree) graphBuilder.requireRule(DDescriptionUtils.getSymlinkTreeTarget(buildTarget));

    // Create a rule that actually builds the binary, and add that
    // rule to the index.
    CxxLink nativeLinkable =
        DDescriptionUtils.createNativeLinkable(
            context.getCellPathResolver(),
            buildTarget.withAppendedFlavors(BINARY_FLAVOR),
            projectFilesystem,
            params,
            graphBuilder,
            DDescriptionUtils.getCxxPlatform(toolchainProvider, dBuckConfig),
            dBuckConfig,
            cxxBuckConfig,
            /* compilerFlags */ ImmutableList.of(),
            args.getSrcs(),
            args.getLinkerFlags(),
            DIncludes.builder()
                .setLinkTree(sourceTree.getSourcePathToOutput())
                .addAllSources(args.getSrcs().getPaths())
                .build());
    graphBuilder.addToIndex(nativeLinkable);

    // Create a Tool for the executable.
    CommandTool.Builder executableBuilder = new CommandTool.Builder();
    executableBuilder.addArg(SourcePathArg.of(nativeLinkable.getSourcePathToOutput()));

    // Return a BinaryBuildRule implementation, so that this works
    // with buck run etc.
    return new DBinary(
        buildTarget,
        projectFilesystem,
        params.withExtraDeps(ImmutableSortedSet.of(nativeLinkable)),
        executableBuilder.build(),
        nativeLinkable.getSourcePathToOutput());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractDBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.addAll(
        CxxPlatforms.getParseTimeDeps(
            DDescriptionUtils.getCxxPlatform(toolchainProvider, dBuckConfig)));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractDBinaryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    SourceList getSrcs();

    ImmutableList<String> getLinkerFlags();
  }
}

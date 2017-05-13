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

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

public class DBinaryDescription
    implements Description<DBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<DBinaryDescription.AbstractDBinaryDescriptionArg>,
        VersionRoot<DBinaryDescriptionArg> {

  public static final Flavor BINARY_FLAVOR = InternalFlavor.of("binary");

  private final DBuckConfig dBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform cxxPlatform;

  public DBinaryDescription(
      DBuckConfig dBuckConfig, CxxBuckConfig cxxBuckConfig, CxxPlatform cxxPlatform) {
    this.dBuckConfig = dBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public Class<DBinaryDescriptionArg> getConstructorArgType() {
    return DBinaryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CellPathResolver cellRoots,
      DBinaryDescriptionArg args)
      throws NoSuchBuildTargetException {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    SymlinkTree sourceTree =
        buildRuleResolver.addToIndex(
            DDescriptionUtils.createSourceSymlinkTree(
                DDescriptionUtils.getSymlinkTreeTarget(params.getBuildTarget()),
                params,
                ruleFinder,
                pathResolver,
                args.getSrcs()));

    // Create a rule that actually builds the binary, and add that
    // rule to the index.
    CxxLink nativeLinkable =
        DDescriptionUtils.createNativeLinkable(
            params.withAppendedFlavor(BINARY_FLAVOR),
            buildRuleResolver,
            cxxPlatform,
            dBuckConfig,
            cxxBuckConfig,
            /* compilerFlags */ ImmutableList.of(),
            args.getSrcs(),
            args.getLinkerFlags(),
            DIncludes.builder()
                .setLinkTree(sourceTree.getSourcePathToOutput())
                .addAllSources(args.getSrcs().getPaths())
                .build());
    buildRuleResolver.addToIndex(nativeLinkable);

    // Create a Tool for the executable.
    CommandTool.Builder executableBuilder = new CommandTool.Builder();
    executableBuilder.addArg(SourcePathArg.of(nativeLinkable.getSourcePathToOutput()));

    // Return a BinaryBuildRule implementation, so that this works
    // with buck run etc.
    return new DBinary(
        params.copyReplacingExtraDeps(Suppliers.ofInstance(ImmutableSortedSet.of(nativeLinkable))),
        ruleFinder,
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
    extraDepsBuilder.addAll(cxxPlatform.getLd().getParseTimeDeps());
  }

  @Override
  public boolean isVersionRoot(ImmutableSet<Flavor> flavors) {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractDBinaryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    SourceList getSrcs();

    ImmutableList<String> getLinkerFlags();
  }
}

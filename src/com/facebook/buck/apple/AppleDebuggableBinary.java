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
package com.facebook.buck.apple;

import com.facebook.buck.cxx.BuildRuleWithBinary;
import com.facebook.buck.cxx.CxxBinary;
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasPostBuildSteps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Depending on the debug type (AppleDebugFormat), it will generate appropriate debug symbols in
 * separate build steps. In any case it will strip out the debug symbols from binary.
 */
public class AppleDebuggableBinary
    extends AbstractBuildRule
    implements HasPostBuildSteps, BuildRuleWithBinary {

  public static final Predicate<TargetNode<?>> STATIC_LIBRARY_ONLY_PREDICATE =
      new Predicate<TargetNode<?>>() {
        @Override
        public boolean apply(TargetNode<?> input) {
          Optional<TargetNode<AppleBinaryDescription.Arg>> binaryNode =
              input.castArg(AppleBinaryDescription.Arg.class);
          Optional<TargetNode<CxxLibraryDescription.Arg>> libraryNode =
              input.castArg(CxxLibraryDescription.Arg.class);
          if (binaryNode.isPresent() || !libraryNode.isPresent()) {
            return false;
          }
          return isStaticLibraryArg(libraryNode.get().getConstructorArg());
        }
  };

  private static boolean isStaticLibraryArg(CxxLibraryDescription.Arg args) {
    if (args.forceStatic.or(false)) {
      return true;
    }
    if (!args.linkStyle.isPresent()) {
      return true;
    }
    return args.linkStyle.get() != Linker.LinkableDepType.SHARED;
  }

  public static final Function<TargetNode<?>, BuildTarget> TO_BUILD_TARGET =
      new Function<TargetNode<?>, BuildTarget>() {
        @Override
        public BuildTarget apply(TargetNode<?> input) {
          return input.getBuildTarget();
        }
  };

  @AddToRuleKey
  private final Tool lldb;

  @AddToRuleKey
  private final Tool dsymutil;

  @AddToRuleKey
  private final Tool strip;

  @AddToRuleKey
  private final BuildRule binaryBuildRule;

  // The location of dSYM
  private final Path dsymOutput;

  @AddToRuleKey
  private final AppleDebugFormat appleDebugFormat;

  public AppleDebuggableBinary(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      BuildRule binaryBuildRule,
      Tool dsymutil,
      Tool lldb,
      Tool strip,
      Path dsymOutput,
      AppleDebugFormat appleDebugFormat) {
    super(buildRuleParams, resolver);
    this.dsymutil = dsymutil;
    this.lldb = lldb;
    this.strip = strip;
    this.binaryBuildRule = binaryBuildRule;
    this.dsymOutput = dsymOutput;
    this.appleDebugFormat = appleDebugFormat;
  }

  public static boolean isBuildRuleDebuggable(
      BuildRule buildRule,
      TargetGraph targetGraph) {
    // stub binary files cannot have dSYMs
    if (buildRule instanceof WriteFile) {
      return false;
    }

    // fat/thin binaries and dynamic libraries may have dSYMs
    if (buildRule instanceof AppleDebuggableBinary ||
        buildRule instanceof FatBinary ||
        buildRule instanceof CxxBinary ||
        buildRule instanceof CxxLink) {
      return true;
    }

    TargetNode<?> node = Preconditions.checkNotNull(targetGraph.get(buildRule.getBuildTarget()));
    Optional<TargetNode<AppleBundleDescription.Arg>> bundleNode =
        node.castArg(AppleBundleDescription.Arg.class);

    if (bundleNode.isPresent()) {
      TargetNode<?> binaryNode = Preconditions.checkNotNull(
          targetGraph.get(bundleNode.get().getConstructorArg().binary));

      // dSYM may exist for bundle if bundle's binary is actually apple binary
      if (binaryNode.castArg(AppleBinaryDescription.Arg.class).isPresent()) {
        return true;
      }

      // dSYM may exist for bundle if bundle's binary is shared apple framework
      Optional<TargetNode<AppleLibraryDescription.Arg>> libraryAsBinaryNode =
          binaryNode.castArg(AppleLibraryDescription.Arg.class);
      if (libraryAsBinaryNode.isPresent()) {
        return !isStaticLibraryArg(libraryAsBinaryNode.get().getConstructorArg());
      }
    }

    return false;
  }

  /**
   * Returns all dependencies that are required in order to produce debuggable binary.
   * @param binary Binary that should be debuggable. All its static libs are required in order to
   *               get all debug symbols.
   * @param targetGraph Target graph required to find all build rules for static libs of the binary
   * @param resolver Resolver required to find all build rules for static libs of the binary
   * @param cxxPlatform cxxPlatform
   * @param depType link style from Args
   * @param appleDebugFormat controls whether debug info will be produced or not
   * @return
   */
  public static ImmutableSortedSet<BuildRule> getDeps(
      BuildRule binary,
      TargetGraph targetGraph,
      final BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType depType,
      AppleDebugFormat appleDebugFormat) {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .add(binary)
        .addAll(
            getTransitiveArchiveBuildRules(
                binary,
                targetGraph,
                resolver,
                cxxPlatform,
                depType,
                appleDebugFormat))
        .build();
  }

  private static Iterable<BuildRule> getTransitiveArchiveBuildRules(
      BuildRule binary,
      TargetGraph targetGraph,
      final BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType depType,
      AppleDebugFormat appleDebugFormat) {
    if (appleDebugFormat == AppleDebugFormat.NONE) {
      return ImmutableList.of();
    }

    TargetNode<?> binaryTargetNode = targetGraph.get((binary.getBuildTarget()));
    TargetGraph subgraph = targetGraph.getSubgraph(ImmutableList.of(binaryTargetNode));
    ImmutableSet<TargetNode<?>> nodes = subgraph.getNodes();
    Set<BuildTarget> buildTargets = Sets.newHashSet();
    buildTargets.addAll(FluentIterable
        .from(nodes)
        .filter(STATIC_LIBRARY_ONLY_PREDICATE)
        .transform(TO_BUILD_TARGET)
        .toSet());
    buildTargets.remove(binaryTargetNode.getBuildTarget());

    ImmutableList.Builder<BuildRule> linkRules = ImmutableList.builder();

    for (BuildRule libraryBuildRule : resolver.getAllRules(buildTargets)) {
      if (libraryBuildRule instanceof CxxLibrary) {
        CxxLibrary nativeLinkable = (CxxLibrary) libraryBuildRule;
        Linker.LinkableDepType linkStyle = NativeLinkables.getLinkStyle(
            nativeLinkable.getPreferredLinkage(cxxPlatform),
            depType);
        try {
          linkRules.add(nativeLinkable.getLibraryLinkRule(cxxPlatform, linkStyle));
        } catch (NoSuchBuildTargetException e) {
          throw new HumanReadableException(
              "Cannot obtain transitive archive rule for linkable %s: %s",
              nativeLinkable,
              e);
        }
      }
    }
    return linkRules.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    List<Step> steps = new ArrayList<>();
    if (appleDebugFormat == AppleDebugFormat.DWARF_AND_DSYM) {
      buildableContext.recordArtifact(dsymOutput);
      steps.add(getGenerateDsymStep());
    }
    steps.add(getStripDebugSymbolsStep());
    return ImmutableList.copyOf(steps);
  }

  private DsymStep getGenerateDsymStep() {
    Preconditions.checkNotNull(binaryBuildRule.getPathToOutput(),
        "Binary build rule " + binaryBuildRule.toString() + " has no output path.");
    return new DsymStep(
        getProjectFilesystem(),
        dsymutil.getEnvironment(getResolver()),
        dsymutil.getCommandPrefix(getResolver()),
        binaryBuildRule.getPathToOutput(),
        dsymOutput);
  }

  private Step getStripDebugSymbolsStep() {
    return new StripDebugSymbolsStep(binaryBuildRule, strip, getProjectFilesystem(), getResolver());
  }

  @Override
  public Path getPathToOutput() {
    return dsymOutput;
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    if (appleDebugFormat == AppleDebugFormat.NONE) {
      return ImmutableList.of();
    }
    return ImmutableList.<Step>of(
        new RegisterDebugSymbolsStep(binaryBuildRule, lldb, getResolver(), dsymOutput));
  }

  @Override
  public BuildRule getBinaryBuildRule() {
    return binaryBuildRule;
  }
}

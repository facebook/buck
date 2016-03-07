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
import com.facebook.buck.log.Logger;
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
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.io.IOException;
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

  private static final Logger LOG = Logger.get(AppleDebuggableBinary.class);
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
  private final Path output;

  @AddToRuleKey
  private final AppleDebugFormat appleDebugFormat;

  public AppleDebuggableBinary(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      BuildRule binaryBuildRule,
      Tool dsymutil,
      Tool lldb,
      Tool strip,
      Path output,
      AppleDebugFormat appleDebugFormat) {
    super(buildRuleParams, resolver);
    this.dsymutil = dsymutil;
    this.lldb = lldb;
    this.strip = strip;
    this.binaryBuildRule = binaryBuildRule;
    this.output = output;
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
      buildableContext.recordArtifact(output);
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
        output);
  }

  private Step getStripDebugSymbolsStep() {
    return new Step() {
      @Override
      public int execute(ExecutionContext context) throws IOException, InterruptedException {
        Preconditions.checkNotNull(binaryBuildRule.getPathToOutput(),
            "Binary build rule " + binaryBuildRule.toString() + " has no output path.");
        // Don't strip binaries which are already code-signed.  Note: we need to use
        // binaryOutputPath instead of binaryPath because codesign will evaluate the
        // entire .app bundle, even if you pass it the direct path to the embedded binary.
        if (!CodeSigning.hasValidSignature(
            context.getProcessExecutor(),
            binaryBuildRule.getPathToOutput())) {
          return (new DefaultShellStep(
              getProjectFilesystem().getRootPath(),
              ImmutableList.<String>builder()
                  .addAll(strip.getCommandPrefix(getResolver()))
                  .add("-S")
                  .add(getProjectFilesystem().resolve(binaryBuildRule.getPathToOutput()).toString())
                  .build(),
              strip.getEnvironment(getResolver())))
              .execute(context);
        } else {
          LOG.info("Not stripping code-signed binary.");
          return 0;
        }
      }

      @Override
      public String getShortName() {
        return "strip binary";
      }

      @Override
      public String getDescription(ExecutionContext context) {
        return String.format(
            "strip debug symbols from binary '%s'",
            binaryBuildRule.getPathToOutput());
      }
    };
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    if (appleDebugFormat == AppleDebugFormat.NONE) {
      return ImmutableList.of();
    }
    return ImmutableList.<Step>of(
        new Step() {
          @Override
          public int execute(ExecutionContext context) throws IOException, InterruptedException {
            ImmutableList<String> lldbCommandPrefix = lldb.getCommandPrefix(getResolver());
            ProcessExecutorParams params = ProcessExecutorParams
                .builder()
                .addCommand(lldbCommandPrefix.toArray(new String[lldbCommandPrefix.size()]))
                .build();
            return context.getProcessExecutor().launchAndExecute(
                params,
                ImmutableSet.<ProcessExecutor.Option>of(),
                Optional.of(
                    String.format("target create %s\ntarget symbols add %s",
                        binaryBuildRule.getPathToOutput(),
                        output)),
                Optional.<Long>absent(),
                Optional.<Function<Process, Void>>absent()).getExitCode();
          }

          @Override
          public String getShortName() {
            return "register debug symbols";
          }

          @Override
          public String getDescription(ExecutionContext context) {
            return String.format(
                "register debug symbols for binary '%s': '%s'",
                binaryBuildRule.getPathToOutput(),
                output);
          }
        });
  }

  @Override
  public BuildRule getBinaryBuildRule() {
    return binaryBuildRule;
  }
}

/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rust;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxGenruleDescription;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.Linkers;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BinaryWrapperRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.ForwardingBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Utilities to generate various kinds of Rust compilation. */
public class RustCompileUtils {
  private RustCompileUtils() {}

  protected static BuildTarget getCompileBuildTarget(
      BuildTarget target, CxxPlatform cxxPlatform, CrateType crateType) {
    return target.withFlavors(cxxPlatform.getFlavor(), crateType.getFlavor());
  }

  // Construct a RustCompileRule with:
  // - all sources
  // - rustc
  // - linker
  // - rustc optim / feature / cfg / user-specified flags
  // - linker args
  // - `--extern <crate>=<rlibpath>` for direct dependencies
  // - `-L dependency=<dir>` for transitive dependencies
  // - `-C relocation-model=pic/static/default/dynamic-no-pic` according to flavor
  // - `--emit metadata` if flavor is "check"
  // - `--crate-type lib/rlib/dylib/cdylib/staticlib` according to flavor
  protected static RustCompileRule createBuild(
      BuildTarget target,
      String crateName,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      RustBuckConfig rustConfig,
      ImmutableList<String> extraFlags,
      ImmutableList<String> extraLinkerFlags,
      Iterable<Arg> linkerInputs,
      CrateType crateType,
      Linker.LinkableDepType depType,
      boolean rpath,
      ImmutableSortedSet<SourcePath> sources,
      SourcePath rootModule)
      throws NoSuchBuildTargetException {
    ImmutableSortedSet<BuildRule> ruledeps = params.getBuildDeps();
    ImmutableList.Builder<Arg> linkerArgs = ImmutableList.builder();

    Stream.concat(rustConfig.getLinkerArgs(cxxPlatform).stream(), extraLinkerFlags.stream())
        .map(StringArg::of)
        .forEach(linkerArgs::add);

    linkerArgs.addAll(linkerInputs);

    ImmutableList.Builder<Arg> args = ImmutableList.builder();
    ImmutableList.Builder<Arg> depArgs = ImmutableList.builder();

    String relocModel;
    if (crateType.isPic()) {
      relocModel = "pic";
    } else {
      relocModel = "static";
    }

    Stream<String> checkArgs;
    if (crateType.isCheck()) {
      args.add(StringArg.of("--emit=metadata"));
      checkArgs = rustConfig.getRustCheckFlags().stream();
    } else {
      checkArgs = Stream.of();
    }

    Stream.of(
            Stream.of(
                String.format("--crate-name=%s", crateName),
                String.format("--crate-type=%s", crateType),
                String.format("-Crelocation-model=%s", relocModel)),
            extraFlags.stream(),
            checkArgs)
        .flatMap(x -> x)
        .map(StringArg::of)
        .forEach(args::add);

    // Find direct and transitive Rust deps. We do this in two passes, since a dependency that's
    // both direct and transitive needs to be listed on the command line in each form.
    //
    // This could end up with a lot of redundant parameters (lots of rlibs in one directory),
    // but Arg isn't comparable, so we can't put it in a Set.

    // First pass - direct deps
    ruledeps
        .stream()
        .filter(RustLinkable.class::isInstance)
        .map(
            rule ->
                ((RustLinkable) rule).getLinkerArg(true, crateType.isCheck(), cxxPlatform, depType))
        .forEach(depArgs::add);

    // Second pass - indirect deps
    new AbstractBreadthFirstTraversal<BuildRule>(
        ruledeps
            .stream()
            .flatMap(r -> r.getBuildDeps().stream())
            .collect(MoreCollectors.toImmutableList())) {
      private final ImmutableSet<BuildRule> empty = ImmutableSet.of();

      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        ImmutableSet<BuildRule> deps = empty;
        if (rule instanceof RustLinkable) {
          deps = rule.getBuildDeps();

          Arg arg =
              ((RustLinkable) rule).getLinkerArg(false, crateType.isCheck(), cxxPlatform, depType);

          depArgs.add(arg);
        }
        return deps;
      }
    }.start();

    // A native crate output is no longer intended for consumption by the Rust toolchain;
    // it's either an executable, or a native library that C/C++ can link with. Rust DYLIBs
    // also need all dependencies available.
    if (crateType.needAllDeps()) {
      ImmutableList<Arg> nativeArgs =
          NativeLinkables.getTransitiveNativeLinkableInput(
                  cxxPlatform,
                  ruledeps,
                  depType,
                  RustLinkable.class::isInstance,
                  RustLinkable.class::isInstance)
              .getArgs();

      // Add necessary rpaths if we're dynamically linking with things
      if (rpath && depType == Linker.LinkableDepType.SHARED) {
        args.add(StringArg.of("-Crpath"));
      }

      linkerArgs.addAll(nativeArgs);
    }

    // If we want shared deps or are building a dynamic rlib, make sure we prefer
    // dynamic dependencies (esp to get dynamic dependency on standard libs)
    if (depType == Linker.LinkableDepType.SHARED || crateType == CrateType.DYLIB) {
      args.add(StringArg.of("-Cprefer-dynamic"));
    }

    String filename = crateType.filenameFor(crateName, cxxPlatform);

    return resolver.addToIndex(
        RustCompileRule.from(
            ruleFinder,
            params.withBuildTarget(target),
            filename,
            rustConfig.getRustCompiler().resolve(resolver),
            rustConfig
                .getLinkerProvider(cxxPlatform, cxxPlatform.getLd().getType())
                .resolve(resolver),
            args.build(),
            depArgs.build(),
            linkerArgs.build(),
            CxxGenruleDescription.fixupSourcePaths(resolver, ruleFinder, cxxPlatform, sources),
            CxxGenruleDescription.fixupSourcePath(resolver, ruleFinder, cxxPlatform, rootModule),
            crateType.hasOutput()));
  }

  public static RustCompileRule requireBuild(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      RustBuckConfig rustConfig,
      ImmutableList<String> extraFlags,
      ImmutableList<String> extraLinkerFlags,
      Iterable<Arg> linkerInputs,
      String crateName,
      CrateType crateType,
      Linker.LinkableDepType depType,
      ImmutableSortedSet<SourcePath> sources,
      SourcePath rootModule)
      throws NoSuchBuildTargetException {
    BuildTarget target = getCompileBuildTarget(params.getBuildTarget(), cxxPlatform, crateType);

    // If this rule has already been generated, return it.
    Optional<RustCompileRule> existing =
        resolver.getRuleOptionalWithType(target, RustCompileRule.class);
    if (existing.isPresent()) {
      return existing.get();
    }

    return createBuild(
        target,
        crateName,
        params,
        resolver,
        ruleFinder,
        cxxPlatform,
        rustConfig,
        extraFlags,
        extraLinkerFlags,
        linkerInputs,
        crateType,
        depType,
        true,
        sources,
        rootModule);
  }

  public static Linker.LinkableDepType getLinkStyle(
      BuildTarget target, Optional<Linker.LinkableDepType> linkStyle) {
    Optional<RustBinaryDescription.Type> type = RustBinaryDescription.BINARY_TYPE.getValue(target);
    Linker.LinkableDepType ret;

    if (type.isPresent()) {
      ret = type.get().getLinkStyle();
    } else if (linkStyle.isPresent()) {
      ret = linkStyle.get();
    } else {
      ret = Linker.LinkableDepType.STATIC;
    }

    // XXX rustc always links executables with "-pie", which requires all objects to be built
    // with a PIC relocation model (-fPIC or -Crelocation-model=pic). Rust code does this by
    // default, but we need to make sure any C/C++ dependencies are also PIC.
    // So for now, remap STATIC -> STATIC_PIC, until we can control rustc's use of -pie.
    if (ret == Linker.LinkableDepType.STATIC) {
      ret = Linker.LinkableDepType.STATIC_PIC;
    }

    return ret;
  }

  public static BinaryWrapperRule createBinaryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      RustBuckConfig rustBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxPlatform defaultCxxPlatform,
      Optional<String> crateName,
      ImmutableSortedSet<String> features,
      Iterator<String> rustcFlags,
      Iterator<String> linkerFlags,
      Linker.LinkableDepType linkStyle,
      boolean rpath,
      ImmutableSortedSet<SourcePath> srcs,
      Optional<SourcePath> crateRoot,
      ImmutableSet<String> defaultRoots,
      boolean isCheck)
      throws NoSuchBuildTargetException {
    final BuildTarget buildTarget = params.getBuildTarget();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    ImmutableList.Builder<String> rustcArgs = ImmutableList.builder();

    RustCompileUtils.addFeatures(buildTarget, features, rustcArgs);

    rustcArgs.addAll(rustcFlags);

    ImmutableList.Builder<String> linkerArgs = ImmutableList.builder();
    linkerArgs.addAll(linkerFlags);

    String crate = crateName.orElse(ruleToCrateName(buildTarget.getShortName()));

    CxxPlatform cxxPlatform =
        cxxPlatforms.getValue(params.getBuildTarget()).orElse(defaultCxxPlatform);

    Pair<SourcePath, ImmutableSortedSet<SourcePath>> rootModuleAndSources =
        getRootModuleAndSources(
            params.getBuildTarget(),
            resolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            crate,
            crateRoot,
            defaultRoots,
            srcs);

    // The target to use for the link rule.
    BuildTarget binaryTarget =
        params
            .getBuildTarget()
            .withAppendedFlavors(
                isCheck ? RustDescriptionEnhancer.RFCHECK : RustDescriptionEnhancer.RFBIN);

    if (isCheck || !rustBuckConfig.getUnflavoredBinaries()) {
      binaryTarget = binaryTarget.withAppendedFlavors(cxxPlatform.getFlavor());
    }

    CommandTool.Builder executableBuilder = new CommandTool.Builder();

    // Special handling for dynamically linked binaries.
    if (linkStyle == Linker.LinkableDepType.SHARED) {

      // Create a symlink tree with for all native shared (NativeLinkable) libraries
      // needed by this binary.
      SymlinkTree sharedLibraries =
          resolver.addToIndex(
              CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
                  ruleFinder,
                  params.getBuildTarget(),
                  params.getProjectFilesystem(),
                  cxxPlatform,
                  params.getBuildDeps(),
                  RustLinkable.class::isInstance,
                  RustLinkable.class::isInstance));

      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      // The shared libraries root is absolute. Also need an absolute path to the linkOutput
      Path absBinaryDir =
          params
              .getBuildTarget()
              .getCellPath()
              .resolve(RustCompileRule.getOutputDir(binaryTarget, params.getProjectFilesystem()));

      linkerArgs.addAll(
          Linkers.iXlinker(
              "-rpath",
              String.format(
                  "%s/%s",
                  cxxPlatform.getLd().resolve(resolver).origin(),
                  absBinaryDir.relativize(sharedLibraries.getRoot()).toString())));

      // Add all the shared libraries and the symlink tree as inputs to the tool that represents
      // this binary, so that users can attach the proper deps.
      executableBuilder.addDep(sharedLibraries);
      executableBuilder.addInputs(sharedLibraries.getLinks().values());

      // Also add Rust shared libraries as runtime deps. We don't need these in the symlink tree
      // because rustc will include their dirs in rpath by default.
      Map<String, SourcePath> rustSharedLibraries =
          getTransitiveRustSharedLibraries(cxxPlatform, params.getBuildDeps());
      executableBuilder.addInputs(rustSharedLibraries.values());
    }

    final RustCompileRule buildRule =
        RustCompileUtils.createBuild(
            binaryTarget,
            crate,
            params,
            resolver,
            ruleFinder,
            cxxPlatform,
            rustBuckConfig,
            rustcArgs.build(),
            linkerArgs.build(),
            /* linkerInputs */ ImmutableList.of(),
            isCheck ? CrateType.CHECKBIN : CrateType.BIN,
            linkStyle,
            rpath,
            rootModuleAndSources.getSecond(),
            rootModuleAndSources.getFirst());

    // Add the binary as the first argument.
    executableBuilder.addArg(SourcePathArg.of(buildRule.getSourcePathToOutput()));

    final CommandTool executable = executableBuilder.build();

    return new BinaryWrapperRule(params.copyAppendingExtraDeps(buildRule), ruleFinder) {

      @Override
      public Tool getExecutableCommand() {
        return executable;
      }

      @Override
      public SourcePath getSourcePathToOutput() {
        return new ForwardingBuildTargetSourcePath(
            getBuildTarget(), buildRule.getSourcePathToOutput());
      }
    };
  }

  /**
   * Given a list of sources, return the one which is the root based on the defaults and user
   * parameters.
   *
   * @param resolver Source path resolver for rule
   * @param crate Name of crate
   * @param defaults Default names for this rule (library, binary, etc)
   * @param sources List of sources
   * @return The matching source
   */
  public static Optional<SourcePath> getCrateRoot(
      SourcePathResolver resolver,
      String crate,
      ImmutableSet<String> defaults,
      Stream<SourcePath> sources) {
    String crateName = String.format("%s.rs", crate);
    ImmutableList<SourcePath> res =
        sources
            .filter(
                src -> {
                  String name = resolver.getRelativePath(src).getFileName().toString();
                  return defaults.contains(name) || name.equals(crateName);
                })
            .collect(MoreCollectors.toImmutableList());

    if (res.size() == 1) {
      return Optional.of(res.get(0));
    } else {
      return Optional.empty();
    }
  }

  public static void addFeatures(
      BuildTarget buildTarget, Iterable<String> features, ImmutableList.Builder<String> args) {
    for (String feature : features) {
      if (feature.contains("\"")) {
        throw new HumanReadableException(
            "%s contains an invalid feature name %s", buildTarget.getFullyQualifiedName(), feature);
      }

      args.add("--cfg", String.format("feature=\"%s\"", feature));
    }
  }

  public static String ruleToCrateName(String rulename) {
    return rulename.replace('-', '_');
  }

  /**
   * Collect all the shared libraries generated by {@link RustLinkable}s found by transitively
   * traversing all unbroken dependency chains of {@link com.facebook.buck.rust.RustLinkable}
   * objects found via the passed in {@link com.facebook.buck.rules.BuildRule} roots.
   *
   * @return a mapping of library name to the library {@link SourcePath}.
   */
  public static Map<String, SourcePath> getTransitiveRustSharedLibraries(
      CxxPlatform cxxPlatform, Iterable<? extends BuildRule> inputs)
      throws NoSuchBuildTargetException {
    ImmutableSortedMap.Builder<String, SourcePath> libs = ImmutableSortedMap.naturalOrder();

    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(inputs) {
      private final ImmutableSet<BuildRule> empty = ImmutableSet.of();

      @Override
      public Iterable<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
        ImmutableSet<BuildRule> deps = empty;
        if (rule instanceof RustLinkable) {
          deps = rule.getBuildDeps();

          RustLinkable rustLinkable = (RustLinkable) rule;

          if (rustLinkable.getPreferredLinkage() != NativeLinkable.Linkage.STATIC) {
            libs.putAll(rustLinkable.getRustSharedLibraries(cxxPlatform));
          }
        }
        return deps;
      }
    }.start();

    return libs.build();
  }

  static Pair<SourcePath, ImmutableSortedSet<SourcePath>> getRootModuleAndSources(
      BuildTarget target,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      String crate,
      Optional<SourcePath> crateRoot,
      ImmutableSet<String> defaultRoots,
      ImmutableSortedSet<SourcePath> srcs)
      throws NoSuchBuildTargetException {

    ImmutableSortedSet<SourcePath> fixedSrcs =
        CxxGenruleDescription.fixupSourcePaths(resolver, ruleFinder, cxxPlatform, srcs);

    Optional<SourcePath> rootModule =
        crateRoot
            .map(Optional::of)
            .orElse(getCrateRoot(pathResolver, crate, defaultRoots, fixedSrcs.stream()));

    return new Pair<>(
        rootModule.orElseThrow(
            () ->
                new HumanReadableException(
                    "Can't find suitable top-level source file for %s: %s",
                    target.getFullyQualifiedName(), fixedSrcs)),
        fixedSrcs);
  }
}

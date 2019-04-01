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

package com.facebook.buck.features.rust;

import static com.facebook.buck.cxx.CxxDescriptionEnhancer.createSharedLibrarySymlinkTreeTarget;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.description.arg.HasDefaultPlatform;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.rules.tool.BinaryWrapperRule;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxGenruleDescription;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.linker.Linkers;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;

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
  // - `-Zsave-analysis` if flavor is "save-analysis"
  // - `--crate-type lib/rlib/dylib/cdylib/staticlib` according to flavor
  private static RustCompileRule createBuild(
      BuildTarget target,
      String crateName,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      RustPlatform rustPlatform,
      RustBuckConfig rustConfig,
      ImmutableList<String> extraFlags,
      ImmutableList<String> extraLinkerFlags,
      Iterable<Arg> linkerInputs,
      CrateType crateType,
      Optional<String> edition,
      LinkableDepType depType,
      boolean rpath,
      ImmutableSortedSet<SourcePath> sources,
      SourcePath rootModule,
      boolean forceRlib,
      boolean preferStatic,
      Iterable<BuildRule> ruledeps,
      Optional<String> incremental) {
    CxxPlatform cxxPlatform = rustPlatform.getCxxPlatform();
    ImmutableList.Builder<Arg> linkerArgs = ImmutableList.builder();

    Optional<String> filename = crateType.filenameFor(target, crateName, cxxPlatform);

    if (crateType == CrateType.CDYLIB) {
      String soname = filename.get();
      Linker linker = cxxPlatform.getLd().resolve(graphBuilder, target.getTargetConfiguration());
      linkerArgs.addAll(StringArg.from(linker.soname(soname)));
    }

    Stream.concat(rustPlatform.getLinkerArgs().stream(), extraLinkerFlags.stream())
        .filter(x -> !x.isEmpty())
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
      checkArgs = rustPlatform.getRustCheckFlags().stream();
    } else {
      checkArgs = Stream.of();
    }

    if (crateType.isSaveAnalysis()) {
      // This is an unstable option - not clear what the path to stabilization is.
      args.add(StringArg.of("-Zsave-analysis"));
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

    if (edition.isPresent()) {
      args.add(StringArg.of(String.format("--edition=%s", edition.get())));
    }

    if (incremental.isPresent()) {
      Path path =
          projectFilesystem
              .getBuckPaths()
              .getTmpDir()
              .resolve("rust-incremental")
              .resolve(incremental.get());

      for (Flavor f : target.getFlavors()) {
        path = path.resolve(f.getName());
      }
      args.add(StringArg.of(String.format("-Cincremental=%s", path)));
    }

    LinkableDepType rustDepType;
    // If we're building a CDYLIB then our Rust dependencies need to be static
    // Alternatively, if we're using forceRlib then anything else needs rlib deps.
    if (depType == LinkableDepType.SHARED && (forceRlib || crateType == CrateType.CDYLIB)) {
      rustDepType = LinkableDepType.STATIC_PIC;
    } else {
      rustDepType = depType;
    }

    // Find direct and transitive Rust deps. We do this in two passes, since a dependency that's
    // both direct and transitive needs to be listed on the command line in each form.
    //
    // This could end up with a lot of redundant parameters (lots of rlibs in one directory),
    // but Arg isn't comparable, so we can't put it in a Set.

    // First pass - direct deps
    RichStream.from(ruledeps)
        .filter(RustLinkable.class::isInstance)
        .map(
            rule ->
                ((RustLinkable) rule)
                    .getLinkerArg(true, crateType.isCheck(), rustPlatform, rustDepType))
        .forEach(depArgs::add);

    // Second pass - indirect deps
    new AbstractBreadthFirstTraversal<BuildRule>(
        RichStream.from(ruledeps)
            .filter(RustLinkable.class)
            .flatMap(r -> RichStream.from(r.getRustLinakbleDeps(rustPlatform)))
            .collect(ImmutableList.toImmutableList())) {
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        Iterable<BuildRule> deps = ImmutableSortedSet.of();
        if (rule instanceof RustLinkable) {
          deps = ((RustLinkable) rule).getRustLinakbleDeps(rustPlatform);

          Arg arg =
              ((RustLinkable) rule)
                  .getLinkerArg(false, crateType.isCheck(), rustPlatform, rustDepType);

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
                  graphBuilder,
                  target.getTargetConfiguration(),
                  ruledeps,
                  depType,
                  r ->
                      r instanceof RustLinkable
                          ? Optional.of(((RustLinkable) r).getRustLinakbleDeps(rustPlatform))
                          : Optional.empty())
              .getArgs();

      // Add necessary rpaths if we're dynamically linking with things
      if (rpath && depType == Linker.LinkableDepType.SHARED) {
        args.add(StringArg.of("-Crpath"));
      }

      linkerArgs.addAll(nativeArgs);
    }

    // If we want shared deps or are building a dynamic rlib, make sure we prefer
    // dynamic dependencies (esp to get dynamic dependency on standard libs)
    if ((!preferStatic && depType == Linker.LinkableDepType.SHARED)
        || crateType == CrateType.DYLIB) {
      args.add(StringArg.of("-Cprefer-dynamic"));
    }

    return RustCompileRule.from(
        ruleFinder,
        target,
        projectFilesystem,
        params,
        filename,
        rustPlatform.getRustCompiler().resolve(graphBuilder, target.getTargetConfiguration()),
        rustPlatform.getLinkerProvider().resolve(graphBuilder, target.getTargetConfiguration()),
        args.build(),
        depArgs.build(),
        linkerArgs.build(),
        CxxGenruleDescription.fixupSourcePaths(graphBuilder, ruleFinder, cxxPlatform, sources),
        CxxGenruleDescription.fixupSourcePath(graphBuilder, ruleFinder, cxxPlatform, rootModule),
        rustConfig.getRemapSrcPaths());
  }

  public static RustCompileRule requireBuild(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      RustPlatform rustPlatform,
      RustBuckConfig rustConfig,
      ImmutableList<String> extraFlags,
      ImmutableList<String> extraLinkerFlags,
      Iterable<Arg> linkerInputs,
      String crateName,
      CrateType crateType,
      Optional<String> edition,
      LinkableDepType depType,
      ImmutableSortedSet<SourcePath> sources,
      SourcePath rootModule,
      boolean forceRlib,
      boolean preferStatic,
      Iterable<BuildRule> deps,
      Optional<String> incremental) {
    return (RustCompileRule)
        graphBuilder.computeIfAbsent(
            getCompileBuildTarget(buildTarget, rustPlatform.getCxxPlatform(), crateType),
            target ->
                createBuild(
                    target,
                    crateName,
                    projectFilesystem,
                    params,
                    graphBuilder,
                    ruleFinder,
                    rustPlatform,
                    rustConfig,
                    extraFlags,
                    extraLinkerFlags,
                    linkerInputs,
                    crateType,
                    edition,
                    depType,
                    true,
                    sources,
                    rootModule,
                    forceRlib,
                    preferStatic,
                    deps,
                    incremental));
  }

  public static Linker.LinkableDepType getLinkStyle(
      BuildTarget target, Optional<Linker.LinkableDepType> linkStyle) {
    Optional<RustBinaryDescription.Type> type = RustBinaryDescription.BINARY_TYPE.getValue(target);
    Linker.LinkableDepType ret;

    if (type.isPresent()) {
      ret = type.get().getLinkStyle();
    } else {
      ret = linkStyle.orElse(LinkableDepType.STATIC);
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

  public static RustPlatform getRustPlatform(
      RustToolchain rustToolchain, BuildTarget target, HasDefaultPlatform hasDefaultPlatform) {
    return rustToolchain
        .getRustPlatforms()
        .getValue(target)
        .orElse(
            hasDefaultPlatform
                .getDefaultPlatform()
                .map(flavor -> rustToolchain.getRustPlatforms().getValue(flavor))
                .orElseGet(rustToolchain::getDefaultRustPlatform));
  }

  static Iterable<BuildTarget> getPlatformParseTimeDeps(
      TargetConfiguration targetConfiguration, RustPlatform rustPlatform) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
    deps.addAll(rustPlatform.getRustCompiler().getParseTimeDeps(targetConfiguration));
    rustPlatform.getLinker().ifPresent(l -> deps.addAll(l.getParseTimeDeps(targetConfiguration)));
    deps.addAll(CxxPlatforms.getParseTimeDeps(targetConfiguration, rustPlatform.getCxxPlatform()));
    return deps.build();
  }

  public static Iterable<BuildTarget> getPlatformParseTimeDeps(
      RustToolchain rustToolchain, BuildTarget buildTarget, HasDefaultPlatform hasDefaultPlatform) {
    return getPlatformParseTimeDeps(
        buildTarget.getTargetConfiguration(),
        getRustPlatform(rustToolchain, buildTarget, hasDefaultPlatform));
  }

  public static BinaryWrapperRule createBinaryBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      RustBuckConfig rustBuckConfig,
      RustPlatform rustPlatform,
      Optional<String> crateName,
      Optional<String> edition,
      ImmutableSortedSet<String> features,
      Iterator<String> rustcFlags,
      Iterator<String> linkerFlags,
      LinkableDepType linkStyle,
      boolean rpath,
      ImmutableSortedSet<SourcePath> srcs,
      Optional<SourcePath> crateRoot,
      ImmutableSet<String> defaultRoots,
      CrateType crateType,
      Iterable<BuildRule> deps) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    ImmutableList.Builder<String> rustcArgs = ImmutableList.builder();

    RustCompileUtils.addFeatures(buildTarget, features, rustcArgs);

    RustCompileUtils.addTargetTripleForFlavor(rustPlatform.getFlavor(), rustcArgs);
    rustcArgs.addAll(rustcFlags);

    ImmutableList.Builder<String> linkerArgs = ImmutableList.builder();
    linkerArgs.addAll(linkerFlags);

    String crate = crateName.orElse(ruleToCrateName(buildTarget.getShortName()));

    CxxPlatform cxxPlatform = rustPlatform.getCxxPlatform();

    Pair<SourcePath, ImmutableSortedSet<SourcePath>> rootModuleAndSources =
        getRootModuleAndSources(
            buildTarget,
            graphBuilder,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            crate,
            crateRoot,
            defaultRoots,
            srcs);

    // The target to use for the link rule.
    BuildTarget binaryTarget = buildTarget.withAppendedFlavors(crateType.getFlavor());

    if (crateType.isCheck() || !rustBuckConfig.getUnflavoredBinaries()) {
      binaryTarget = binaryTarget.withAppendedFlavors(cxxPlatform.getFlavor());
    }

    boolean forceRlib = rustBuckConfig.getForceRlib();
    boolean preferStatic = rustBuckConfig.getPreferStaticLibs();

    CommandTool.Builder executableBuilder = new CommandTool.Builder();

    // Special handling for dynamically linked binaries.
    if (linkStyle == Linker.LinkableDepType.SHARED) {

      // Create a symlink tree with for all native shared (NativeLinkable) libraries
      // needed by this binary.
      SymlinkTree sharedLibraries =
          (SymlinkTree)
              graphBuilder.computeIfAbsent(
                  createSharedLibrarySymlinkTreeTarget(buildTarget, cxxPlatform.getFlavor()),
                  target ->
                      CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
                          target,
                          projectFilesystem,
                          graphBuilder,
                          ruleFinder,
                          cxxPlatform,
                          deps,
                          r ->
                              r instanceof RustLinkable
                                  ? Optional.of(
                                      ((RustLinkable) r).getRustLinakbleDeps(rustPlatform))
                                  : Optional.empty()));

      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      // The shared libraries root is absolute. Also need an absolute path to the linkOutput
      Path absBinaryDir =
          buildTarget
              .getCellPath()
              .resolve(RustCompileRule.getOutputDir(binaryTarget, projectFilesystem));

      linkerArgs.addAll(
          Linkers.iXlinker(
              "-rpath",
              String.format(
                  "%s/%s",
                  cxxPlatform
                      .getLd()
                      .resolve(graphBuilder, buildTarget.getTargetConfiguration())
                      .origin(),
                  absBinaryDir.relativize(sharedLibraries.getRoot()).toString())));

      // Add all the shared libraries and the symlink tree as inputs to the tool that represents
      // this binary, so that users can attach the proper deps.
      executableBuilder.addNonHashableInput(sharedLibraries.getRootSourcePath());
      executableBuilder.addInputs(sharedLibraries.getLinks().values());

      // Also add Rust shared libraries as runtime deps. We don't need these in the symlink tree
      // because rustc will include their dirs in rpath by default. We won't have any if we're
      // forcing the use of rlibs.
      Map<String, SourcePath> rustSharedLibraries =
          getTransitiveRustSharedLibraries(rustPlatform, deps, forceRlib);
      executableBuilder.addInputs(rustSharedLibraries.values());
    }

    RustCompileRule buildRule =
        (RustCompileRule)
            graphBuilder.computeIfAbsent(
                binaryTarget,
                target ->
                    createBuild(
                        target,
                        crate,
                        projectFilesystem,
                        params,
                        graphBuilder,
                        ruleFinder,
                        rustPlatform,
                        rustBuckConfig,
                        rustcArgs.build(),
                        linkerArgs.build(),
                        /* linkerInputs */ ImmutableList.of(),
                        crateType,
                        edition,
                        linkStyle,
                        rpath,
                        rootModuleAndSources.getSecond(),
                        rootModuleAndSources.getFirst(),
                        forceRlib,
                        preferStatic,
                        deps,
                        rustBuckConfig.getIncremental(rustPlatform.getFlavor().getName())));

    // Add the binary as the first argument.
    executableBuilder.addArg(SourcePathArg.of(buildRule.getSourcePathToOutput()));

    CommandTool executable = executableBuilder.build();

    return new BinaryWrapperRule(
        buildTarget, projectFilesystem, params.copyAppendingExtraDeps(buildRule)) {

      @Override
      public Tool getExecutableCommand() {
        return executable;
      }

      @Override
      public SourcePath getSourcePathToOutput() {
        return ForwardingBuildTargetSourcePath.of(
            getBuildTarget(), buildRule.getSourcePathToOutput());
      }
    };
  }

  /**
   * Given a list of sources, return the one which is the root based on the defaults and user
   * parameters.
   *
   * @param resolver SourcePathResolver for rule
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
            .collect(ImmutableList.toImmutableList());

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
   * traversing all unbroken dependency chains of {@link
   * com.facebook.buck.features.rust.RustLinkable} objects found via the passed in {@link BuildRule}
   * roots.
   *
   * @return a mapping of library name to the library {@link SourcePath}.
   */
  public static Map<String, SourcePath> getTransitiveRustSharedLibraries(
      RustPlatform rustPlatform, Iterable<? extends BuildRule> inputs, boolean forceRlib) {
    ImmutableSortedMap.Builder<String, SourcePath> libs = ImmutableSortedMap.naturalOrder();

    new AbstractBreadthFirstTraversal<BuildRule>(inputs) {
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        Iterable<BuildRule> deps = ImmutableSet.of();
        if (rule instanceof RustLinkable) {
          RustLinkable rustLinkable = (RustLinkable) rule;

          if (!rustLinkable.isProcMacro()) {
            deps = rustLinkable.getRustLinakbleDeps(rustPlatform);

            if (!forceRlib && rustLinkable.getPreferredLinkage() != NativeLinkable.Linkage.STATIC) {
              libs.putAll(rustLinkable.getRustSharedLibraries(rustPlatform));
            }
          }
        }
        return deps;
      }
    }.start();

    return libs.build();
  }

  static Pair<SourcePath, ImmutableSortedSet<SourcePath>> getRootModuleAndSources(
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      String crate,
      Optional<SourcePath> crateRoot,
      ImmutableSet<String> defaultRoots,
      ImmutableSortedSet<SourcePath> srcs) {

    ImmutableSortedSet<SourcePath> fixedSrcs =
        CxxGenruleDescription.fixupSourcePaths(graphBuilder, ruleFinder, cxxPlatform, srcs);

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

  /**
   * Approximate what Cargo does - it computes a hash based on the crate version and its
   * dependencies. Buck will deal with the dependencies and we don't need to worry about the
   * version, but we do need to make sure that two crates with the same name in the build are
   * distinct - so compute the hash from the full target path.
   *
   * @param target Which target we're computing the hash for
   * @return Truncated MD5 hash of the target path
   */
  static String hashForTarget(BuildTarget target) {
    String name = target.getUnflavoredBuildTarget().getFullyQualifiedName();
    Hasher hasher = Hashing.md5().newHasher();
    HashCode hash = hasher.putString(name, StandardCharsets.UTF_8).hash();
    return hash.toString().substring(0, 16);
  }

  /** Given a Rust flavor, return a target triple or null if none known. */
  @VisibleForTesting
  public static @Nullable String targetTripleForFlavor(Flavor flavor) {
    List<String> parts = Splitter.on('-').limit(2).splitToList(flavor.getName());

    if (parts.size() != 2) {
      return null;
    }

    String platform = parts.get(0);
    if (!platform.equals(ApplePlatform.IPHONEOS.getName())
        && !platform.equals(ApplePlatform.IPHONESIMULATOR.getName())) {
      return null;
    }

    String rawArch = parts.get(1);
    String rustArch;
    if (rawArch.equals("armv7")) {
      // armv7 is not part of Architecture.
      rustArch = "armv7";
    } else {
      Architecture arch = Architecture.fromName(parts.get(1));
      rustArch = arch.toString();
    }

    return rustArch + "-apple-ios";
  }

  /** Add the appropriate --target option to the given rustc args if the given flavor is known. */
  public static void addTargetTripleForFlavor(Flavor flavor, ImmutableList.Builder<String> args) {
    String triple = targetTripleForFlavor(flavor);
    if (triple != null) {
      args.add("--target", triple);
    }
  }
}

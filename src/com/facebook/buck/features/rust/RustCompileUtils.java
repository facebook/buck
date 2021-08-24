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

package com.facebook.buck.features.rust;

import static com.facebook.buck.cxx.CxxDescriptionEnhancer.createSharedLibrarySymlinkTreeTarget;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.description.arg.HasDefaultPlatform;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.MappedSymlinkTree;
import com.facebook.buck.core.rules.tool.BinaryWrapperRule;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxGenruleDescription;
import com.facebook.buck.cxx.CxxLocationMacroExpander;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.linker.impl.Linkers;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroups;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.OutputMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;

/** Utilities to generate various kinds of Rust compilation. */
public class RustCompileUtils {
  private RustCompileUtils() {}

  /** Add a RustCompileRule to the graphBuilder if it isn't already present */
  public static RustCompileRule requireBuild(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      RustPlatform rustPlatform,
      RustBuckConfig rustConfig,
      DownwardApiConfig downwardApiConfig,
      ImmutableSortedMap<String, Arg> environment,
      ImmutableList<Arg> extraRustcFlags,
      ImmutableList<Arg> extraLinkerFlags,
      Iterable<Arg> linkerInputs,
      String crateName,
      CrateType crateType,
      Optional<String> edition,
      LinkableDepType depType,
      ImmutableSortedMap<SourcePath, Optional<String>> mappedSources,
      String rootModule,
      boolean forceRlib,
      boolean preferStatic,
      Iterable<BuildRule> deps,
      ImmutableMap<String, BuildTarget> depsAliases,
      Optional<String> incremental) {

    return (RustCompileRule)
        graphBuilder.computeIfAbsent(
            getCompileBuildTarget(buildTarget, rustPlatform.getCxxPlatform(), crateType),
            target ->
                createBuild(
                    target,
                    crateName,
                    projectFilesystem,
                    graphBuilder,
                    rustPlatform,
                    rustConfig,
                    downwardApiConfig,
                    environment,
                    extraRustcFlags,
                    extraLinkerFlags,
                    linkerInputs,
                    crateType,
                    edition,
                    depType,
                    true,
                    mappedSources,
                    rootModule,
                    forceRlib,
                    preferStatic,
                    deps,
                    depsAliases,
                    incremental));
  }

  protected static BuildTarget getCompileBuildTarget(
      BuildTarget target, CxxPlatform cxxPlatform, CrateType crateType) {
    return target.withAppendedFlavors(cxxPlatform.getFlavor(), crateType.getFlavor());
  }

  /** Return true if the rule is for something that's RustLinkable, but not a proc macro */
  public static boolean nonProcMacroRustLinkable(BuildRule rule) {
    if (rule instanceof RustLinkable) {
      RustLinkable linkable = (RustLinkable) rule;
      return !linkable.isProcMacro();
    }
    return false;
  }

  public static Pair<ImmutableList<Arg>, ImmutableSortedMap<String, Arg>> getRustcFlagsAndEnv(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      RustPlatform rustPlatform,
      ImmutableList<StringArg> ruleFlags,
      RustCommonArgs args) {

    StringWithMacrosConverter converter =
        RustCompileUtils.getMacroExpander(context, buildTarget, rustPlatform.getCxxPlatform());

    ImmutableList.Builder<Arg> rustcArgs = ImmutableList.builder();

    RustCompileUtils.addFeatures(buildTarget, args.getFeatures(), rustcArgs);
    RustCompileUtils.addTargetTripleForFlavor(rustPlatform.getFlavor(), rustcArgs);
    rustcArgs.addAll(ruleFlags);
    args.getRustcFlags().forEach(arg -> rustcArgs.add(converter.convert(arg)));

    ImmutableList<StringWithMacros> platFlags =
        args.getPlatformRustcFlags().get(rustPlatform.getFlavor());
    if (platFlags != null) {
      for (StringWithMacros flag : platFlags) {
        rustcArgs.add(converter.convert(flag));
      }
    }

    ImmutableSortedMap<String, Arg> env =
        ImmutableSortedMap.copyOf(Maps.transformValues(args.getEnv(), converter::convert));

    return new Pair<>(rustcArgs.build(), env);
  }

  public static ImmutableList<Arg> getLinkerFlags(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      RustPlatform rustPlatform,
      RustLinkableArgs args) {

    StringWithMacrosConverter converter =
        RustCompileUtils.getMacroExpander(context, buildTarget, rustPlatform.getCxxPlatform());

    ImmutableList.Builder<Arg> builder = new ImmutableList.Builder<>();

    args.getLinkerFlags().forEach(arg -> builder.add(converter.convert(arg)));
    args.getPlatformLinkerFlags()
        .getMatchingValues(rustPlatform.getFlavor().toString())
        .forEach(platargs -> platargs.forEach(arg -> builder.add(converter.convert(arg))));

    return builder.build();
  }

  /**
   * Construct a RustCompileRule with:
   *
   * <ul>
   *   <li>all sources
   *   <li>rustc
   *   <li>linker
   *   <li>rustc optim / feature / cfg / user-specified flags
   *   <li>linker args
   *   <li>`--extern <crate>=<rlibpath>` for direct dependencies
   *   <li>`-L dependency=<dir>` for transitive dependencies
   *   <li>`-C relocation-model=pic/static/default/dynamic-no-pic` according to flavor
   *   <li>`--emit metadata` if flavor is "check"
   *   <li>`-Zsave-analysis` if flavor is "save-analysis"
   *   <li>`--crate-type lib/rlib/dylib/cdylib/staticlib` according to flavor
   * </ul>
   */
  private static RustCompileRule createBuild(
      BuildTarget target,
      String crateName,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      RustPlatform rustPlatform,
      RustBuckConfig rustConfig,
      DownwardApiConfig downwardApiConfig,
      ImmutableSortedMap<String, Arg> environment,
      ImmutableList<Arg> extraFlags,
      ImmutableList<Arg> extraLinkerFlags,
      Iterable<Arg> linkerInputs,
      CrateType crateType,
      Optional<String> edition,
      LinkableDepType depType,
      boolean rpath,
      ImmutableSortedMap<SourcePath, Optional<String>> mappedSources,
      String rootModule,
      boolean forceRlib,
      boolean preferStatic,
      Iterable<BuildRule> depRules,
      ImmutableMap<String, BuildTarget> depsAliases,
      Optional<String> incremental) {
    ImmutableList.Builder<Arg> args = ImmutableList.builder();
    ImmutableList.Builder<Arg> depArgs = ImmutableList.builder();
    ImmutableList.Builder<Arg> linkerArgs = ImmutableList.builder();

    CxxPlatform cxxPlatform = rustPlatform.getCxxPlatform();
    String filename = crateType.filenameFor(target, crateName, cxxPlatform);

    // Use the C linker configuration for CDYLIB
    if (crateType == CrateType.CDYLIB) {
      Linker linker = cxxPlatform.getLd().resolve(graphBuilder, target.getTargetConfiguration());
      linkerArgs.addAll(StringArg.from(linker.soname(filename)));
    }

    linkerArgs.addAll(rustPlatform.getLinkerArgs());
    linkerArgs.addAll(extraLinkerFlags);
    linkerArgs.addAll(linkerInputs);

    String relocModel;

    if (crateType.isPic() || isPicDefault(rustPlatform)) {
      relocModel = "pic";
    } else {
      relocModel = "static";
    }

    args.add(StringArg.of("--crate-name=" + crateName));
    args.add(StringArg.of("--crate-type=" + crateType));
    args.add(StringArg.of("-Crelocation-model=" + relocModel));
    args.addAll(extraFlags);

    args.add(StringArg.of("--edition=" + edition.orElse(rustConfig.getEdition())));

    if (crateType.isCheck()) {
      args.add(StringArg.of("--emit=metadata"));
      args.addAll(rustPlatform.getRustCheckFlags());
    } else if (crateType.isDoc()) {
      args.addAll(rustPlatform.getRustDocFlags());
    }

    if (crateType.isSaveAnalysis()) {
      // This is an unstable option - not clear what the path to stabilization is.
      args.add(StringArg.of("-Zsave-analysis"));
    }

    // Don't require "extern crate proc_macro"
    if (crateType == CrateType.PROC_MACRO || crateType == CrateType.DOC_MACRO) {
      // pathless extern was stabilized in 1.42
      // https://blog.rust-lang.org/2020/03/12/Rust-1.42.html#use-proc_macrotokenstream-now-works
      args.add(StringArg.of("--extern=proc_macro"));
    }

    if (incremental.isPresent()) {
      Path path =
          projectFilesystem
              .getBuckPaths()
              .getTmpDir()
              .resolve("rust-incremental")
              .resolve(incremental.get());

      for (Flavor f : target.getFlavors().getSet()) {
        path = path.resolve(f.getName());
      }
      args.add(StringArg.of("-Cincremental=" + path));
    }

    LinkableDepType rustDepType;
    // Work out the linkage for our dependencies
    switch (depType) {
      case SHARED:
        // If we're building a CDYLIB then our Rust dependencies need to be static
        // Alternatively, if we're using forceRlib then anything else needs rlib deps.
        if (forceRlib || crateType == CrateType.CDYLIB) {
          rustDepType = LinkableDepType.STATIC_PIC;
        } else {
          rustDepType = LinkableDepType.SHARED;
        }
        break;

      case STATIC:
        // If we're PIC, all our dependencies need to be as well
        if (crateType.isPic()) {
          rustDepType = LinkableDepType.STATIC_PIC;
        } else {
          rustDepType = LinkableDepType.STATIC;
        }
        break;

      case STATIC_PIC:
      default: // Unnecessary?
        rustDepType = depType;
        break;
    }

    // Build reverse mapping from build targets to aliases. This might be a 1:many relationship
    // (ie, the crate may import another crate multiple times under multiple names). If there's
    // nothing here then the default name is used.
    Multimap<BuildTarget, String> revAliasMap =
        Multimaps.invertFrom(
            Multimaps.forMap(depsAliases), MultimapBuilder.hashKeys().arrayListValues().build());

    Optional<String> htmlRootUrlPrefix = rustConfig.getRustdocExternHtmlRootUrlPrefix();

    // Find direct and transitive Rust deps. We do this in two passes, since a dependency that's
    // both direct and transitive needs to be listed on the command line in each form.
    //
    // This could end up with a lot of redundant parameters (lots of rlibs in one directory),
    // but Arg isn't comparable, so we can't put it in a Set.

    // First pass - direct deps
    for (BuildRule rule : depRules) {
      if (rule instanceof RustLinkable) {
        addDependencyArgs(
            rule,
            rustPlatform,
            crateType,
            depArgs,
            revAliasMap,
            rustDepType,
            Optional.of(target),
            projectFilesystem);

        if (htmlRootUrlPrefix.isPresent() && crateType.isDoc()) {
          BuildTarget buildTarget = rule.getBuildTarget();

          // HTML links to a different cell are not supported yet.
          if (buildTarget.getCell().getName() != "") {
            continue;
          }

          String depCrate = ((RustLinkable) rule).getCrate();
          String depAbsTarget = rule.getBuildTarget().getCellRelativeName();
          String depTarget = depAbsTarget.replaceAll("^/+", "");

          Collection<String> depAliasesColl = revAliasMap.get(buildTarget);
          Stream<Optional<String>> depAliases;
          if (depAliasesColl.isEmpty()) {
            depAliases = Stream.of(Optional.empty());
          } else {
            depAliases = depAliasesColl.stream().map(Optional::of);
          }
          depAliases.forEach(
              alias ->
                  depArgs.add(
                      StringArg.of(
                          "--extern-html-root-url="
                              + alias.orElse(depCrate)
                              + "="
                              + htmlRootUrlPrefix.get()
                              + "/"
                              + depTarget)));
        }
      }
    }

    // Second pass - indirect deps
    new AbstractBreadthFirstTraversal<BuildRule>(
        RichStream.from(depRules)
            .filter(RustLinkable.class::isInstance)
            .flatMap(r -> RichStream.from(((RustLinkable) r).getRustLinkableDeps(rustPlatform)))
            .collect(ImmutableList.toImmutableList())) {
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        Iterable<BuildRule> deps = ImmutableSortedSet.of();

        if (rule instanceof RustLinkable) {
          RustLinkable rl = (RustLinkable) rule;

          if (!rl.isProcMacro()) {
            // Only keep adding transitive deps for non-proc-macro dependencies
            deps = ((RustLinkable) rule).getRustLinkableDeps(rustPlatform);
          }

          // Add an indirect dependency for this rule.
          addDependencyArgs(
              rule,
              rustPlatform,
              crateType,
              depArgs,
              revAliasMap,
              rustDepType,
              Optional.empty(),
              projectFilesystem);
        }
        return deps;
      }
    }.start();

    // A native crate output is no longer intended for consumption by the Rust toolchain;
    // it's either an executable, or a native library that C/C++ can link with. Rust DYLIBs
    // also need all dependencies available.
    if (crateType.needAllDeps()) {
      // Get the topologically sorted native linkables.
      ImmutableMap<BuildTarget, NativeLinkableGroup> roots =
          NativeLinkableGroups.getNativeLinkableRoots(
              depRules,
              (Function<? super BuildRule, Optional<Iterable<? extends BuildRule>>>)
                  r -> rustNativeRootsPassthrough(r, rustPlatform));

      ImmutableList<Arg> nativeArgs =
          NativeLinkables.getTransitiveNativeLinkableInput(
                  graphBuilder,
                  target.getTargetConfiguration(),
                  Iterables.transform(
                      roots.values(), g -> g.getNativeLinkable(cxxPlatform, graphBuilder)),
                  depType)
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

    Tool compiler =
        (crateType.isDoc())
            ? rustPlatform.getRustdoc().resolve(graphBuilder, target.getTargetConfiguration())
            : rustPlatform.getRustCompiler().resolve(graphBuilder, target.getTargetConfiguration());

    return RustCompileRule.from(
        graphBuilder,
        target,
        projectFilesystem,
        filename,
        rustPlatform,
        compiler,
        rustPlatform.getLinkerProvider().resolve(graphBuilder, target.getTargetConfiguration()),
        args.build(),
        depArgs.build(),
        linkerArgs.build(),
        environment,
        mappedSources,
        rootModule,
        rustConfig.getRemapSrcPaths(),
        rustPlatform.getXcrunSdkPath().map(path -> path.toString()),
        downwardApiConfig.isEnabledForRust());
  }

  // PDC PIC type for shared linking means we're targeting Windows
  // where PIC relocation model should always be used.
  // It's just a tricky way to check that target platform is Windows,
  // it doesn't mean we should generate non-PIC.
  private static boolean isPicDefault(RustPlatform rustPlatform) {
    return rustPlatform.getCxxPlatform().getPicTypeForSharedLinking() == PicType.PDC;
  }

  // Helper for NativeLinkableGroups.getNativeLinkableRoots to get the C++ dependencies of a Rust
  // rule, while excluding any proc macros (since they're never a candidate for native linking).
  //
  // Return Option.empty() if this is a rule we should consider a root.
  // Return Option.of(deps) to pass through this rule and move to its leaves.
  // Procmacros are passthrough, but we don't care about their deps.
  private static Optional<Iterable<? extends BuildRule>> rustNativeRootsPassthrough(
      BuildRule rule, RustPlatform rustPlatform) {
    Optional<Iterable<? extends BuildRule>> ret = Optional.empty();

    if (rule instanceof RustLinkable) {
      RustLinkable rl = (RustLinkable) rule;
      Iterable<BuildRule> deps = ImmutableList.of();

      if (!rl.isProcMacro()) {
        deps = rl.getRustLinkableDeps(rustPlatform);
      }
      ret = Optional.of(deps);
    }

    return ret;
  }

  private static void addDependencyArgs(
      BuildRule rule,
      RustPlatform rustPlatform,
      CrateType crateType,
      ImmutableList.Builder<Arg> depArgs,
      Multimap<BuildTarget, String> revAliasMap,
      LinkableDepType rustDepType,
      Optional<BuildTarget> directDependent,
      ProjectFilesystem dependentFilesystem) {
    Collection<String> coll = revAliasMap.get(rule.getBuildTarget());
    Stream<Optional<String>> aliases;
    if (coll.isEmpty()) {
      aliases = Stream.of(Optional.empty());
    } else {
      aliases = coll.stream().map(Optional::of);
    }
    aliases // now stream of Optional<alias>
        .map(
            alias ->
                ((RustLinkable) rule)
                    .getLinkerArg(
                        directDependent,
                        dependentFilesystem,
                        crateType,
                        rustPlatform,
                        rustDepType,
                        alias))
        .forEach(depArgs::add);
  }

  public static Linker.LinkableDepType getLinkStyle(
      BuildTarget target, Optional<Linker.LinkableDepType> linkStyle, RustPlatform rustPlatform) {
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
    // If PIC is already generated by default (e.g. on Windows) we don't need to do this.
    if (ret == Linker.LinkableDepType.STATIC && !isPicDefault(rustPlatform)) {
      ret = Linker.LinkableDepType.STATIC_PIC;
    }

    return ret;
  }

  /** Gets the {@link UnresolvedRustPlatform} for a target. */
  public static UnresolvedRustPlatform getRustPlatform(
      RustToolchain rustToolchain, FlavorSet flavors, HasDefaultPlatform hasDefaultPlatform) {
    return rustToolchain
        .getRustPlatforms()
        .getValue(flavors)
        .orElse(
            hasDefaultPlatform
                .getDefaultPlatform()
                .map(flavor -> rustToolchain.getRustPlatforms().getValue(flavor))
                .orElseGet(rustToolchain::getDefaultRustPlatform));
  }

  public static UnresolvedRustPlatform getRustPlatform(
      RustToolchain rustToolchain, BuildTarget target, HasDefaultPlatform hasDefaultPlatform) {
    return getRustPlatform(rustToolchain, target.getFlavors(), hasDefaultPlatform);
  }

  static Iterable<BuildTarget> getPlatformParseTimeDeps(
      TargetConfiguration targetConfiguration, UnresolvedRustPlatform rustPlatform) {
    return rustPlatform.getParseTimeDeps(targetConfiguration);
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
      DownwardApiConfig downwardApiConfig,
      RustPlatform rustPlatform,
      Optional<String> crateName,
      Optional<String> edition,
      ImmutableSortedMap<String, Arg> environment,
      ImmutableList<Arg> rustcFlags,
      ImmutableList<Arg> linkerFlags,
      LinkableDepType linkStyle,
      boolean rpath,
      ImmutableSortedSet<SourcePath> sources,
      ImmutableSortedMap<SourcePath, String> mappedSources,
      Optional<String> crateRoot,
      ImmutableSet<String> defaultRoots,
      CrateType crateType,
      Iterable<BuildRule> deps,
      ImmutableMap<String, BuildTarget> depsAliases) {
    ImmutableList.Builder<Arg> linkerArgs = ImmutableList.builder();
    linkerArgs.addAll(linkerFlags);

    String crate = crateName.orElse(ruleToCrateName(buildTarget.getShortName()));

    CxxPlatform cxxPlatform = rustPlatform.getCxxPlatform();

    Pair<String, ImmutableSortedMap<SourcePath, Optional<String>>> rootModuleAndSources =
        getRootModuleAndSources(
            projectFilesystem,
            buildTarget,
            graphBuilder,
            cxxPlatform,
            crate,
            crateRoot,
            defaultRoots,
            sources,
            mappedSources);

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
      MappedSymlinkTree sharedLibraries =
          (MappedSymlinkTree)
              graphBuilder.computeIfAbsent(
                  createSharedLibrarySymlinkTreeTarget(buildTarget, cxxPlatform.getFlavor()),
                  target ->
                      CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
                          target,
                          projectFilesystem,
                          graphBuilder,
                          cxxPlatform,
                          deps,
                          r -> rustNativeRootsPassthrough(r, rustPlatform)));

      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      // The shared libraries root is absolute. Also need an absolute path to the linkOutput
      AbsPath absBinaryDir =
          projectFilesystem.resolve(RustCompileRule.getOutputDir(binaryTarget, projectFilesystem));

      StreamSupport.stream(
              Linkers.iXlinker(
                      "-rpath",
                      String.format(
                          "%s/%s",
                          cxxPlatform
                              .getLd()
                              .resolve(graphBuilder, buildTarget.getTargetConfiguration())
                              .origin(),
                          absBinaryDir.relativize(sharedLibraries.getRoot()).toString()))
                  .spliterator(),
              false)
          .map(StringArg::of)
          .forEach(linkerArgs::add);

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
                        graphBuilder,
                        rustPlatform,
                        rustBuckConfig,
                        downwardApiConfig,
                        environment,
                        rustcFlags,
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
                        depsAliases,
                        rustBuckConfig.getIncremental(rustPlatform.getFlavor().getName())));

    // Add the binary as the first argument.
    executableBuilder.addArg(SourcePathArg.of(buildRule.getSourcePathToOutput()));

    CommandTool executable = executableBuilder.build();

    return new BinaryWrapperRule(
        buildTarget, projectFilesystem, params.copyAppendingExtraDeps(buildRule)) {

      @Override
      public Tool getExecutableCommand(OutputLabel outputLabel) {
        return executable;
      }

      @Override
      public SourcePath getSourcePathToOutput() {
        return ForwardingBuildTargetSourcePath.of(
            getBuildTarget(), buildRule.getSourcePathToOutput());
      }
    };
  }

  public static void addFeatures(
      BuildTarget buildTarget, Iterable<String> features, ImmutableList.Builder<Arg> args) {
    for (String feature : features) {
      if (feature.contains("\"")) {
        throw new HumanReadableException(
            "%s contains an invalid feature name %s", buildTarget.getFullyQualifiedName(), feature);
      }

      args.add(StringArg.of("--cfg"), StringArg.of(String.format("feature=\"%s\"", feature)));
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
        if (nonProcMacroRustLinkable(rule)) {
          RustLinkable rustLinkable = (RustLinkable) rule;

          deps = rustLinkable.getRustLinkableDeps(rustPlatform);

          if (!forceRlib
              && rustLinkable.getPreferredLinkage() != NativeLinkableGroup.Linkage.STATIC) {
            libs.putAll(rustLinkable.getRustSharedLibraries(rustPlatform));
          }
        }
        return deps;
      }
    }.start();

    return libs.build();
  }

  /**
   * Given a list of sources, return the one which is the root based on the defaults and user
   * parameters.
   *
   * @param resolver SourcePathResolverAdapter for rule
   * @param crate Name of crate
   * @param defaults Default names for this rule (library, binary, etc)
   * @param sources List of sources
   * @return The unique matching source - if there are not exactly one, return Optional.empty
   */
  public static Optional<String> getCrateRoot(
      SourcePathResolverAdapter resolver,
      String crate,
      ImmutableSet<String> defaults,
      Stream<Path> sources) {
    String crateName = String.format("%s.rs", crate);
    ImmutableList<String> res =
        sources
            .filter(
                src -> {
                  String name = src.getFileName().toString();
                  return defaults.contains(name) || name.equals(crateName);
                })
            .map(src -> src.toString())
            .collect(ImmutableList.toImmutableList());

    if (res.size() == 1) {
      return Optional.of(res.get(0));
    } else {
      return Optional.empty();
    }
  }

  /**
   * Returns the path to the root module source, and the complete set of sources. The sources are
   * always turned into a map, though unmapped sources are mapped to Optional.empty(). The root
   * module is what's passed to rustc as the input source file, and may not exist until the
   * symlinking phase happens.
   */
  static Pair<String, ImmutableSortedMap<SourcePath, Optional<String>>> getRootModuleAndSources(
      ProjectFilesystem projectFilesystem,
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      String crate,
      Optional<String> crateRoot,
      ImmutableSet<String> defaultRoots,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedMap<SourcePath, String> mappedSrcs) {

    ImmutableSortedMap.Builder<SourcePath, Optional<String>> fixedBuilder =
        ImmutableSortedMap.naturalOrder();

    srcs.stream()
        .map(
            src ->
                CxxGenruleDescription.fixupSourcePath(graphBuilder, cxxPlatform.getFlavor(), src))
        .forEach(src -> fixedBuilder.put(src, Optional.empty()));
    mappedSrcs
        .entrySet()
        .forEach(
            ent ->
                fixedBuilder.put(
                    CxxGenruleDescription.fixupSourcePath(
                        graphBuilder, cxxPlatform.getFlavor(), ent.getKey()),
                    Optional.of(ent.getValue())));

    ImmutableSortedMap<SourcePath, Optional<String>> fixed = fixedBuilder.build();

    SourcePathResolverAdapter resolver = graphBuilder.getSourcePathResolver();
    Stream<Path> filenames =
        Stream.concat(
            srcs.stream()
                .map(
                    src ->
                        CxxGenruleDescription.fixupSourcePath(
                            graphBuilder, cxxPlatform.getFlavor(), src))
                .map(sp -> resolver.getCellUnsafeRelPath(sp).getPath()),
            mappedSrcs.values().stream()
                .map(
                    path ->
                        target
                            .getCellRelativeBasePath()
                            .getPath()
                            .toPath(projectFilesystem.getFileSystem())
                            .resolve(path)));

    Optional<String> rootModule =
        crateRoot
            .map(
                name ->
                    target
                        .getCellRelativeBasePath()
                        .getPath()
                        .toPath(projectFilesystem.getFileSystem())
                        .resolve(name)
                        .toString())
            .map(Optional::of)
            .orElseGet(() -> getCrateRoot(resolver, crate, defaultRoots, filenames));

    return new Pair<>(
        rootModule.orElseThrow(
            () ->
                new HumanReadableException(
                    "Can't find suitable top-level source file for %s: %s",
                    target.getFullyQualifiedName(), fixed)),
        fixed);
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
    String rawArch = parts.get(1);
    String rustArch;
    if (platform.equals(ApplePlatform.IPHONEOS.getName())
        || platform.equals(ApplePlatform.IPHONESIMULATOR.getName())) {
      // This is according to https://forge.rust-lang.org/platform-support.html
      if (rawArch.equals("armv7")) {
        // armv7 is not part of Architecture.
        rustArch = "armv7";
      } else {
        Architecture arch = Architecture.fromName(parts.get(1));
        if (arch == Architecture.X86_32) {
          rustArch = "i386";
        } else {
          rustArch = arch.toString();
        }
      }
      return rustArch + "-apple-ios";
    } else if (platform.equals(ApplePlatform.MACOSX.getName())
        || platform.equals(ApplePlatform.MACOSXCATALYST.getName())) {
      switch (rawArch) {
        case "arm64":
          rustArch = "aarch64";
          break;
        case "x86_64":
          rustArch = rawArch;
          break;
        case "i386":
          // Rust does not support i386 target for macOS 32bit. Furthermore, i686 is Tier 3,
          // so by default, it's not part of the official compiler builds. But let's support it,
          // in case there's some unforeseen use case.
          rustArch = "i686";
          break;
        default:
          return null;
      }

      return rustArch + "-apple-darwin";
    } else if (platform.equals("android")) {
      // This is according to https://forge.rust-lang.org/platform-support.html
      if (rawArch.equals("armv7")) {
        // The only difference I see between
        // thumbv7neon-linux-androideabi and armv7-linux-androideabi
        // is that the former does not set +d16, but d16 support is
        // part of armeabi-v7a per
        // https://developer.android.com/ndk/guides/abis.html#v7a.
        return "armv7-linux-androideabi";
      } else {
        // We want aarch64-linux-android, i686-linux-android, or x86_64-linux-android.
        Architecture arch = Architecture.fromName(parts.get(1));
        if (arch == Architecture.X86_32) {
          rustArch = "i686";
        } else {
          rustArch = arch.toString();
        }
        return rustArch + "-linux-android";
      }
    } else {
      return null;
    }
  }

  /** Add the appropriate --target option to the given rustc args if the given flavor is known. */
  public static void addTargetTripleForFlavor(Flavor flavor, ImmutableList.Builder<Arg> args) {
    String triple = targetTripleForFlavor(flavor);
    if (triple != null) {
      args.add(StringArg.of("--target"), StringArg.of(triple));
    }
  }

  /** Return a macro expander for a string with macros */
  public static StringWithMacrosConverter getMacroExpander(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      CxxPlatform cxxPlatform) {
    ImmutableList<MacroExpander<? extends Macro, ?>> expanders =
        ImmutableList.of(
            new CxxLocationMacroExpander(cxxPlatform),
            new OutputMacroExpander(),
            new ExecutableMacroExpander<>(ExecutableMacro.class));

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            context.getCellPathResolver().getCellNameResolver(),
            context.getActionGraphBuilder(),
            expanders,
            Optional.of(cxxPlatform.getCompilerDebugPathSanitizer().sanitizer(Optional.empty())));

    return macrosConverter;
  }
}

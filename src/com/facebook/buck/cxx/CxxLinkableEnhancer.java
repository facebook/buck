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

package com.facebook.buck.cxx;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.linker.HasImportLibrary;
import com.facebook.buck.cxx.toolchain.linker.HasLTO;
import com.facebook.buck.cxx.toolchain.linker.HasLinkerMap;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.ExtraOutputsDeriver;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable.Linkage;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Streams;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public class CxxLinkableEnhancer {
  private static final Logger LOG = Logger.get(CxxLinkableEnhancer.class);

  private static final EnumSet<Linker.LinkType> SONAME_REQUIRED_LINK_TYPES =
      EnumSet.of(Linker.LinkType.SHARED, Linker.LinkType.MACH_O_BUNDLE);

  // Utility class doesn't instantiate.
  private CxxLinkableEnhancer() {}

  public static CxxLink createCxxLinkableBuildRule(
      CellPathResolver cellPathResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      Path output,
      ImmutableMap<String, Path> extraOutputs,
      ImmutableList<Arg> args,
      LinkableDepType runtimeDepType,
      CxxLinkOptions linkOptions,
      Optional<LinkOutputPostprocessor> postprocessor) {

    Linker linker = cxxPlatform.getLd().resolve(ruleResolver, target.getTargetConfiguration());

    // Build up the arguments to pass to the linker.
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Add flags to generate linker map if supported.
    if (linker instanceof HasLinkerMap && LinkerMapMode.isLinkerMapEnabledForBuildTarget(target)) {
      argsBuilder.addAll(((HasLinkerMap) linker).linkerMap(output));
    }

    // Add lto object path if thin LTO is on.
    if (linker instanceof HasLTO && linkOptions.getThinLto()) {
      argsBuilder.addAll(((HasLTO) linker).thinLTO(output));
    } else if (linker instanceof HasLTO && linkOptions.getFatLto()) {
      argsBuilder.addAll(((HasLTO) linker).fatLTO(output));
    }

    if (linker instanceof HasImportLibrary) {
      argsBuilder.addAll(((HasImportLibrary) linker).importLibrary(output));
    }

    // Pass any platform specific or extra linker flags.
    argsBuilder.addAll(
        SanitizedArg.from(
            cxxPlatform.getCompilerDebugPathSanitizer().sanitize(Optional.empty()),
            cxxPlatform.getLdflags()));

    argsBuilder.addAll(args);

    // Add all arguments needed to link in the C/C++ platform runtime.
    argsBuilder.addAll(StringArg.from(cxxPlatform.getRuntimeLdflags().get(runtimeDepType)));

    ImmutableList<Arg> ldArgs = argsBuilder.build();
    ImmutableMap<String, Path> allExtraOutputs = extraOutputs;

    Optional<ExtraOutputsDeriver> extraOutputsDeriver = linker.getExtraOutputsDeriver();
    if (extraOutputsDeriver.isPresent()) {
      ImmutableMap<String, Path> derivedExtraOutputs =
          extraOutputsDeriver
              .get()
              .deriveExtraOutputsFromArgs(
                  Arg.stringify(ldArgs, DefaultSourcePathResolver.from(ruleFinder)), output);
      if (!derivedExtraOutputs.isEmpty()) {
        allExtraOutputs =
            ImmutableMap.<String, Path>builder()
                .putAll(extraOutputs)
                .putAll(derivedExtraOutputs)
                .build();
      }
    }

    return new CxxLink(
        target,
        projectFilesystem,
        ruleFinder,
        cellPathResolver,
        linker,
        output,
        allExtraOutputs,
        ldArgs,
        postprocessor,
        cxxBuckConfig.getLinkScheduleInfo(),
        cxxBuckConfig.shouldCacheLinks(),
        linkOptions.getThinLto(),
        linkOptions.getFatLto());
  }

  /**
   * Construct a {@link CxxLink} rule that builds a native linkable from top-level input objects and
   * a dependency tree of {@link NativeLinkable} dependencies.
   *
   * @param nativeLinkableDeps library dependencies that the linkable links in
   * @param immediateLinkableInput framework and libraries of the linkable itself
   * @param cellPathResolver
   */
  public static CxxLink createCxxLinkableBuildRule(
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      Linker.LinkType linkType,
      Optional<String> soname,
      Path output,
      ImmutableList<String> extraOutputNames,
      Linker.LinkableDepType depType,
      CxxLinkOptions linkOptions,
      Iterable<? extends NativeLinkable> nativeLinkableDeps,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      ImmutableSet<BuildTarget> linkWholeDeps,
      NativeLinkableInput immediateLinkableInput,
      Optional<LinkOutputPostprocessor> postprocessor,
      CellPathResolver cellPathResolver) {

    // Soname should only ever be set when linking a "shared" library.
    Preconditions.checkState(!soname.isPresent() || SONAME_REQUIRED_LINK_TYPES.contains(linkType));

    // Bundle loaders are only supported for Mach-O bundle libraries
    Preconditions.checkState(
        !bundleLoader.isPresent() || linkType == Linker.LinkType.MACH_O_BUNDLE);

    // Collect and topologically sort our deps that contribute to the link.
    Collection<NativeLinkableInput> nativeLinkableInputs =
        graphBuilder
            .getParallelizer()
            .maybeParallelizeTransform(
                Collections2.filter(
                    NativeLinkables.getNativeLinkables(
                        cxxPlatform, graphBuilder, nativeLinkableDeps, depType),
                    linkable -> !blacklist.contains(linkable.getBuildTarget())),
                nativeLinkable -> {
                  Linkage link = nativeLinkable.getPreferredLinkage(cxxPlatform);
                  NativeLinkableInput input =
                      nativeLinkable.getNativeLinkableInput(
                          cxxPlatform,
                          NativeLinkables.getLinkStyle(link, depType),
                          linkWholeDeps.contains(nativeLinkable.getBuildTarget()),
                          graphBuilder,
                          target.getTargetConfiguration());
                  LOG.verbose("Native linkable %s returned input %s", nativeLinkable, input);
                  return input;
                });
    // Construct a list out of the stream rather than passing in an iterable via ::iterator as
    // the latter will never evaluate stream elements in parallel.
    NativeLinkableInput linkableInput =
        NativeLinkableInput.concat(
            Iterables.concat(ImmutableList.of(immediateLinkableInput), nativeLinkableInputs));

    // Build up the arguments to pass to the linker.
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // If we're doing a shared build, pass the necessary flags to the linker, including setting
    // the soname.
    if (linkType == Linker.LinkType.SHARED) {
      argsBuilder.addAll(
          cxxPlatform
              .getLd()
              .resolve(graphBuilder, target.getTargetConfiguration())
              .getSharedLibFlag());
    } else if (linkType == Linker.LinkType.MACH_O_BUNDLE) {
      argsBuilder.add(StringArg.of("-bundle"));
      // It's possible to build a Mach-O bundle without a bundle loader (logic tests, for example).
      if (bundleLoader.isPresent()) {
        argsBuilder.add(StringArg.of("-bundle_loader"), SourcePathArg.of(bundleLoader.get()));
      }
    }
    if (soname.isPresent()) {
      argsBuilder.addAll(
          StringArg.from(
              cxxPlatform
                  .getLd()
                  .resolve(graphBuilder, target.getTargetConfiguration())
                  .soname(soname.get())));
    }

    // Add all arguments from our dependencies.
    argsBuilder.addAll(linkableInput.getArgs());

    // Add all shared libraries
    if (!linkableInput.getLibraries().isEmpty()) {
      addSharedLibrariesLinkerArgs(
          cxxPlatform,
          resolver,
          ImmutableSortedSet.copyOf(linkableInput.getLibraries()),
          argsBuilder);
    }

    // Add framework args
    if (!linkableInput.getFrameworks().isEmpty()) {
      addFrameworkLinkerArgs(
          cxxPlatform,
          resolver,
          ImmutableSortedSet.copyOf(linkableInput.getFrameworks()),
          argsBuilder);
    }

    Linker.LinkableDepType runtimeDepType = depType;
    if (cxxRuntimeType.orElse(Linker.CxxRuntimeType.DYNAMIC) == Linker.CxxRuntimeType.STATIC) {
      runtimeDepType = Linker.LinkableDepType.STATIC;
    }

    ImmutableList<Arg> allArgs = argsBuilder.build();

    return createCxxLinkableBuildRule(
        cellPathResolver,
        cxxBuckConfig,
        cxxPlatform,
        projectFilesystem,
        graphBuilder,
        ruleFinder,
        target,
        output,
        deriveSupplementaryOutputPathsFromMainOutputPath(output, extraOutputNames),
        allArgs,
        runtimeDepType,
        linkOptions,
        postprocessor);
  }

  private static void addSharedLibrariesLinkerArgs(
      CxxPlatform cxxPlatform,
      SourcePathResolver resolver,
      ImmutableSortedSet<FrameworkPath> allLibraries,
      ImmutableList.Builder<Arg> argsBuilder) {

    argsBuilder.add(new SharedLibraryLinkArgs(allLibraries, cxxPlatform, resolver));

    // Add all libraries link args
    argsBuilder.add(new FrameworkLibraryLinkArgs(allLibraries));
  }

  private static void addFrameworkLinkerArgs(
      CxxPlatform cxxPlatform,
      SourcePathResolver resolver,
      ImmutableSortedSet<FrameworkPath> allFrameworks,
      ImmutableList.Builder<Arg> argsBuilder) {

    argsBuilder.add(new FrameworkLinkerArgs(allFrameworks, cxxPlatform, resolver));

    // Add all framework link args
    argsBuilder.add(frameworksToLinkerArg(allFrameworks));
  }

  @VisibleForTesting
  static Arg frameworksToLinkerArg(ImmutableSortedSet<FrameworkPath> frameworkPaths) {
    return new FrameworkToLinkerArg(frameworkPaths);
  }

  public static CxxLink createCxxLinkableSharedBuildRule(
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      Path output,
      ImmutableMap<String, Path> extraOutputs,
      Optional<String> soname,
      ImmutableList<? extends Arg> args,
      CellPathResolver cellPathResolver) {
    ImmutableList.Builder<Arg> linkArgsBuilder = ImmutableList.builder();
    linkArgsBuilder.addAll(
        cxxPlatform
            .getLd()
            .resolve(ruleResolver, target.getTargetConfiguration())
            .getSharedLibFlag());
    if (soname.isPresent()) {
      linkArgsBuilder.addAll(
          StringArg.from(
              cxxPlatform
                  .getLd()
                  .resolve(ruleResolver, target.getTargetConfiguration())
                  .soname(soname.get())));
    }
    linkArgsBuilder.addAll(args);
    ImmutableList<Arg> linkArgs = linkArgsBuilder.build();
    return createCxxLinkableBuildRule(
        cellPathResolver,
        cxxBuckConfig,
        cxxPlatform,
        projectFilesystem,
        ruleResolver,
        ruleFinder,
        target,
        output,
        extraOutputs,
        linkArgs,
        Linker.LinkableDepType.SHARED,
        CxxLinkOptions.of(),
        Optional.empty());
  }

  /**
   * Derive supplementary output paths based on the main output path.
   *
   * @param output main output path.
   * @param supplementaryOutputNames supplementary output names.
   * @return Map of names to supplementary output paths.
   */
  public static ImmutableMap<String, Path> deriveSupplementaryOutputPathsFromMainOutputPath(
      Path output, Iterable<String> supplementaryOutputNames) {
    return Streams.stream(supplementaryOutputNames)
        .collect(
            ImmutableMap.toImmutableMap(
                name -> name,
                name -> output.getParent().resolve(output.getFileName() + "-" + name)));
  }

  private static class FrameworkLinkerArgs extends FrameworkPathArg {
    @AddToRuleKey final AddsToRuleKeyFunction<FrameworkPath, Path> frameworkPathToSearchPath;

    public FrameworkLinkerArgs(
        ImmutableSortedSet<FrameworkPath> allFrameworks,
        CxxPlatform cxxPlatform,
        SourcePathResolver resolver) {
      super(allFrameworks);
      frameworkPathToSearchPath =
          CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, resolver);
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver resolver) {
      ImmutableSortedSet<Path> searchPaths =
          frameworkPaths.stream()
              .map(frameworkPathToSearchPath)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
      for (Path searchPath : searchPaths) {
        consumer.accept("-F");
        consumer.accept(searchPath.toString());
      }
    }
  }

  private static class FrameworkToLinkerArg extends FrameworkPathArg {
    public FrameworkToLinkerArg(ImmutableSortedSet<FrameworkPath> frameworkPaths) {
      super(frameworkPaths);
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver resolver) {
      for (FrameworkPath frameworkPath : frameworkPaths) {
        consumer.accept("-framework");
        consumer.accept(frameworkPath.getName(resolver::getAbsolutePath));
      }
    }
  }

  private static class FrameworkLibraryLinkArgs extends FrameworkPathArg {
    public FrameworkLibraryLinkArgs(ImmutableSortedSet<FrameworkPath> allLibraries) {
      super(allLibraries);
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver resolver) {
      for (FrameworkPath frameworkPath : frameworkPaths) {
        String libName =
            MorePaths.stripPathPrefixAndExtension(
                frameworkPath.getFileName(resolver::getAbsolutePath), "lib");
        // libraries set can contain path-qualified libraries, or just library
        // search paths.
        // Assume these end in '../lib' and filter out here.
        if (libName.isEmpty()) {
          continue;
        }
        consumer.accept("-l" + libName);
      }
    }
  }

  private static class SharedLibraryLinkArgs extends FrameworkPathArg {
    @AddToRuleKey final AddsToRuleKeyFunction<FrameworkPath, Path> frameworkPathToSearchPath;

    public SharedLibraryLinkArgs(
        ImmutableSortedSet<FrameworkPath> allLibraries,
        CxxPlatform cxxPlatform,
        SourcePathResolver resolver) {
      super(allLibraries);
      frameworkPathToSearchPath =
          CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, resolver);
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver resolver) {
      ImmutableSortedSet<Path> searchPaths =
          frameworkPaths.stream()
              .map(frameworkPathToSearchPath)
              .filter(Objects::nonNull)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
      for (Path searchPath : searchPaths) {
        consumer.accept("-L");
        consumer.accept(searchPath.toString());
      }
    }
  }
}

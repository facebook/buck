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

import static com.google.common.base.Predicates.notNull;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.util.EnumSet;

import javax.annotation.Nullable;

public class CxxLinkableEnhancer {

  private static final EnumSet<Linker.LinkType> SONAME_REQUIRED_LINK_TYPES = EnumSet.of(
      Linker.LinkType.SHARED,
      Linker.LinkType.MACH_O_BUNDLE
  );

  // Utility class doesn't instantiate.
  private CxxLinkableEnhancer() {}

  /**
   * Construct a {@link CxxLink} rule that builds a native linkable from top-level input objects
   * and a dependency tree of {@link NativeLinkable} dependencies.
   */
  public static CxxLink createCxxLinkableBuildRule(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      BuildRuleParams params,
      final SourcePathResolver resolver,
      BuildTarget target,
      Linker.LinkType linkType,
      Optional<String> soname,
      Path output,
      ImmutableList<Arg> args,
      Linker.LinkableDepType depType,
      Iterable<? extends BuildRule> nativeLinkableDeps,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      ImmutableSet<FrameworkPath> frameworks) {

    // Soname should only ever be set when linking a "shared" library.
    Preconditions.checkState(!soname.isPresent() || SONAME_REQUIRED_LINK_TYPES.contains(linkType));

    // Bundle loaders are only supported for Mach-O bundle libraries
    Preconditions.checkState(
        !bundleLoader.isPresent() || linkType == Linker.LinkType.MACH_O_BUNDLE);

    Linker linker = cxxPlatform.getLd();

    // Collect and topologically sort our deps that contribute to the link.
    NativeLinkableInput linkableInput =
        NativeLinkableInput.concat(
            FluentIterable
                .from(
                    Maps.filterKeys(
                        NativeLinkables.getNativeLinkables(
                            cxxPlatform,
                            FluentIterable.from(nativeLinkableDeps)
                                .filter(NativeLinkable.class),
                            depType),
                        Predicates.not(Predicates.in(blacklist))).values())
                .transform(
                    NativeLinkables.getNativeLinkableInputFunction(
                        targetGraph,
                        cxxPlatform,
                        depType)));

    // Build up the arguments to pass to the linker.
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Pass any platform specific or extra linker flags.
    argsBuilder.addAll(
        SanitizedArg.from(
            cxxPlatform.getDebugPathSanitizer().sanitize(Optional.<Path>absent()),
            cxxPlatform.getLdflags()));

    // If we're doing a shared build, pass the necessary flags to the linker, including setting
    // the soname.
    if (linkType == Linker.LinkType.SHARED) {
      argsBuilder.add(new StringArg("-shared"));
    } else if (linkType == Linker.LinkType.MACH_O_BUNDLE) {
      argsBuilder.add(new StringArg("-bundle"));
      // It's possible to build a Mach-O bundle without a bundle loader (logic tests, for example).
      if (bundleLoader.isPresent()) {
        argsBuilder.add(
            new StringArg("-bundle_loader"),
            new SourcePathArg(resolver, bundleLoader.get()));
      }
    }
    if (soname.isPresent()) {
      argsBuilder.addAll(StringArg.from(linker.soname(soname.get())));
    }

    // Add all the top-level arguments.
    argsBuilder.addAll(args);

    // Add all arguments from our dependencies.
    argsBuilder.addAll(linkableInput.getArgs());

    // Add all shared libraries
    addSharedLibrariesLinkerArgs(
        cxxPlatform,
        resolver,
        ImmutableSortedSet.copyOf(linkableInput.getLibraries()),
        argsBuilder);

    // Add framework args - from both linkable dependancies and the frameworks for the binary
    addFrameworkLinkerArgs(
        cxxPlatform,
        resolver,
        mergeFrameworks(linkableInput, frameworks),
        argsBuilder);

    // Add all arguments needed to link in the C/C++ platform runtime.
    Linker.LinkableDepType runtimeDepType = depType;
    if (cxxRuntimeType.or(Linker.CxxRuntimeType.DYNAMIC) == Linker.CxxRuntimeType.STATIC) {
      runtimeDepType = Linker.LinkableDepType.STATIC;
    }
    argsBuilder.addAll(StringArg.from(cxxPlatform.getRuntimeLdflags().get(runtimeDepType)));

    final ImmutableList<Arg> allArgs = argsBuilder.build();

    // Build the C/C++ link step.
    return new CxxLink(
        // Construct our link build rule params.  The important part here is combining the build
        // rules that construct our object file inputs and also the deps that build our
        // dependencies.
        params.copyWithChanges(
            target,
            new Supplier<ImmutableSortedSet<BuildRule>>() {
              @Override
              public ImmutableSortedSet<BuildRule> get() {
                return FluentIterable.from(allArgs)
                    .transformAndConcat(Arg.getDepsFunction(resolver))
                    .toSortedSet(Ordering.natural());
              }
            },
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        resolver,
        cxxPlatform.getLd(),
        output,
        allArgs);
  }

  private static ImmutableSortedSet<FrameworkPath> mergeFrameworks(
      NativeLinkableInput nativeLinkable,
      ImmutableSet<FrameworkPath> frameworkPaths) {
    return ImmutableSortedSet.<FrameworkPath>naturalOrder()
        .addAll(nativeLinkable.getFrameworks())
        .addAll(frameworkPaths)
        .build();
  }

  private static void addSharedLibrariesLinkerArgs(
      CxxPlatform cxxPlatform,
      SourcePathResolver resolver,
      ImmutableSortedSet<FrameworkPath> allLibraries,
      ImmutableList.Builder<Arg> argsBuilder) {

    Optional<ImmutableSortedSet<FrameworkPath>> libraries = Optional.of(allLibraries);
    ImmutableSet<Path> librarySearchPaths = CxxDescriptionEnhancer.getFrameworkSearchPaths(
        libraries,
        cxxPlatform,
        resolver);

    for (Path unsanitizedLibrarySearchPath : getLibrarySearchDirectories(librarySearchPaths)) {
      argsBuilder.add(new StringArg("-L"));
      argsBuilder.add(
          new SanitizedArg(
              cxxPlatform.getDebugPathSanitizer().sanitize(Optional.<Path>absent()),
              unsanitizedLibrarySearchPath.toString()));
    }

    // Add all libraries link args
    ImmutableList<String> librariesStringArgs = libraries
        .transform(librariesToLinkerFlagsFunction(resolver))
        .get();
    for (String libraryStringArg : librariesStringArgs) {
      argsBuilder.add(new StringArg(libraryStringArg));
    }
  }

  private static ImmutableSet<Path> getLibrarySearchDirectories(ImmutableSet<Path> libraries) {
    return FluentIterable.from(libraries)
        .transform(
            new Function<Path, Path>() {
              @Nullable
              @Override
              public Path apply(Path input) {
                return input.getParent();
              }
            }
        ).filter(notNull())
        .toSet();
  }

  private static void addFrameworkLinkerArgs(
      CxxPlatform cxxPlatform,
      SourcePathResolver resolver,
      ImmutableSortedSet<FrameworkPath> allFrameworks,
      ImmutableList.Builder<Arg> argsBuilder) {

    Optional<ImmutableSortedSet<FrameworkPath>> frameworks = Optional.of(allFrameworks);
    // Add all framework search path args
    ImmutableSet<Path> frameworkSearchPaths = CxxDescriptionEnhancer.getFrameworkSearchPaths(
        frameworks,
        cxxPlatform,
        resolver);
    for (Path unsanitizedFrameworkSearchPath : frameworkSearchPaths) {
      argsBuilder.add(new StringArg("-F"));
      argsBuilder.add(
          new SanitizedArg(
              cxxPlatform.getDebugPathSanitizer().sanitize(Optional.<Path>absent()),
              unsanitizedFrameworkSearchPath.toString()));
    }

    // Add all framework link args
    ImmutableList<String> frameworkStringArgs = frameworks
        .transform(frameworksToLinkerFlagsFunction(resolver))
        .get();
    for (String frameworkStringArg : frameworkStringArgs) {
      argsBuilder.add(new StringArg(frameworkStringArg));
    }
  }

  public static final Predicate<String> CONTAINS_LIBRARY_NAME =
      new Predicate<String>() {
        @Override
        public boolean apply(@Nullable String input) {
          return (input != null && !input.equals("-l"));
        }
      };

  @VisibleForTesting
  static Function<
      ImmutableSortedSet<FrameworkPath>,
      ImmutableList<String>> librariesToLinkerFlagsFunction(final SourcePathResolver resolver) {
    return new Function<ImmutableSortedSet<FrameworkPath>, ImmutableList<String>>() {
      @Override
      public ImmutableList<String> apply(ImmutableSortedSet<FrameworkPath> input) {
        return FluentIterable
            .from(input)
            .transform(linkerFlagsForLibraryFunction(resolver.deprecatedPathFunction()))
            // libraries set can contain path-qualified libraries, or just library search paths.
            // Assume these end in '../lib' and filter out here.
            .filter(CONTAINS_LIBRARY_NAME)
            .toList();
      }
    };
  }

  private static Function<FrameworkPath, String> linkerFlagsForLibraryFunction(
      final Function<SourcePath, Path> resolver) {
    return new Function<FrameworkPath, String>() {
      @Override
      public String apply(FrameworkPath input) {
        return "-l" + MorePaths.stripPathPrefixAndExtension(input.getFileName(resolver), "lib");
      }
    };
  }

  @VisibleForTesting
  static Function<
      ImmutableSortedSet<FrameworkPath>,
      ImmutableList<String>> frameworksToLinkerFlagsFunction(final SourcePathResolver resolver) {
    return new Function<ImmutableSortedSet<FrameworkPath>, ImmutableList<String>>() {
      @Override
      public ImmutableList<String> apply(ImmutableSortedSet<FrameworkPath> input) {
        return FluentIterable
            .from(input)
            .transformAndConcat(
                linkerFlagsForFrameworkPathFunction(resolver.deprecatedPathFunction()))
            .toList();
      }
    };
  }

  private static Function<FrameworkPath, Iterable<String>> linkerFlagsForFrameworkPathFunction(
      final Function<SourcePath, Path> resolver) {
    return new Function<FrameworkPath, Iterable<String>>() {
      @Override
      public Iterable<String> apply(FrameworkPath input) {
        return ImmutableList.of("-framework", input.getName(resolver));
      }
    };
  }

}

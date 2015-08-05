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
package com.facebook.buck.android;

import com.facebook.buck.cxx.CxxHeaders;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.macros.EnvironmentVariableMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.MoreStrings;
import com.facebook.buck.util.environment.Platform;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Map;
import java.util.regex.Pattern;

public class NdkLibraryDescription implements Description<NdkLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("ndk_library");

  private static final Flavor MAKEFILE_FLAVOR = ImmutableFlavor.of("makefile");

  private static final Pattern EXTENSIONS_REGEX =
      Pattern.compile(
              ".*\\." +
              MoreStrings.regexPatternForAny("mk", "h", "hpp", "c", "cpp", "cc", "cxx") + "$");

  public static final MacroHandler MACRO_HANDLER = new MacroHandler(
      ImmutableMap.<String, MacroExpander>of(
          "env", new EnvironmentVariableMacroExpander(Platform.detect())
      )
  );

  private final Optional<String> ndkVersion;
  private final ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> cxxPlatforms;

  public NdkLibraryDescription(
      Optional<String> ndkVersion,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> cxxPlatforms) {
    this.ndkVersion = ndkVersion;
    this.cxxPlatforms = Preconditions.checkNotNull(cxxPlatforms);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  private Iterable<String> escapeForMakefile(Iterable<String> args) {
    ImmutableList.Builder<String> escapedArgs = ImmutableList.builder();

    for (String arg : args) {
      String escapedArg = arg;

      // The ndk-build makefiles make heavy use of the "eval" function to propagate variables,
      // which means we need to perform additional makefile escaping for *every* "eval" that
      // gets used.  Turns out there are three "evals", so we escape a total of four times
      // including the initial escaping.  Since the makefiles eventually hand-off these values
      // to the shell, we first perform bash escaping.
      //
      escapedArg = Escaper.escapeAsShellString(escapedArg);
      for (int i = 0; i < 4; i++) {
        escapedArg = Escaper.escapeAsMakefileValueString(escapedArg);
      }

      // We run ndk-build from the root of the NDK, so fixup paths that use the relative path to
      // the buck out directory.
      if (arg.startsWith(BuckConstant.BUCK_OUTPUT_DIRECTORY)) {
        escapedArg = "$(BUCK_PROJECT_DIR)/" + escapedArg;
      }

      escapedArgs.add(escapedArg);
    }

    return escapedArgs.build();
  }

  private String getTargetArchAbi(NdkCxxPlatforms.TargetCpuType cpuType) {
    switch (cpuType) {
      case ARM:
        return "armeabi";
      case ARMV7:
        return "armeabi-v7a";
      case X86:
        return "x86";
      case MIPS:
        return "mips";
      default:
        throw new IllegalStateException();
    }
  }

  @VisibleForTesting
  protected static Path getGeneratedMakefilePath(BuildTarget target) {
    return BuildTargets.getGenPath(target, "Android.%s.mk");
  }

  /**
   * @return a {@link BuildRule} which generates a Android.mk which pulls in the local Android.mk
   *     file and also appends relevant preprocessor and linker flags to use C/C++ library deps.
   */
  private Pair<BuildRule, Iterable<BuildRule>> generateMakefile(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      BuildRuleResolver resolver) {

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableList.Builder<String> outputLinesBuilder = ImmutableList.builder();
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();

    for (Map.Entry<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> entry : cxxPlatforms.entrySet()) {
      CxxPlatform cxxPlatform = entry.getValue().getCxxPlatform();

      CxxPreprocessorInput cxxPreprocessorInput;
      try {
        // Collect the preprocessor input for all C/C++ library deps.  We search *through* other
        // NDK library rules.
        cxxPreprocessorInput = CxxPreprocessorInput.concat(
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                targetGraph,
                cxxPlatform,
                params.getDeps(),
                Predicates.instanceOf(NdkLibrary.class)));
      } catch (CxxHeaders.ConflictingHeadersException e) {
        throw e.getHumanReadableExceptionForBuildTarget(params.getBuildTarget());
      }

      // We add any dependencies from the C/C++ preprocessor input to this rule, even though
      // it technically should be added to the top-level rule.
      deps.addAll(
          pathResolver.filterBuildRuleInputs(
              cxxPreprocessorInput.getIncludes().getPrefixHeaders()));
      deps.addAll(
          pathResolver.filterBuildRuleInputs(
              cxxPreprocessorInput.getIncludes().getNameToPathMap().values()));
      deps.addAll(resolver.getAllRules(cxxPreprocessorInput.getRules()));

      // Add in the transitive preprocessor flags contributed by C/C++ library rules into the
      // NDK build.
      Iterable<String> ppflags = Iterables.concat(
          cxxPreprocessorInput.getPreprocessorFlags().get(CxxSource.Type.C),
          MoreIterables.zipAndConcat(
              Iterables.cycle("-I"),
              FluentIterable.from(cxxPreprocessorInput.getHeaderMaps())
                  .transform(Functions.toStringFunction())),
          MoreIterables.zipAndConcat(
              Iterables.cycle("-I"),
              FluentIterable.from(cxxPreprocessorInput.getIncludeRoots())
                  .transform(Functions.toStringFunction())),
          MoreIterables.zipAndConcat(
              Iterables.cycle("-isystem"),
              FluentIterable.from(cxxPreprocessorInput.getIncludeRoots())
                  .transform(Functions.toStringFunction())));
      String localCflags = Joiner.on(' ').join(escapeForMakefile(ppflags));

      // Collect the native linkable input for all C/C++ library deps.  We search *through* other
      // NDK library rules.
      NativeLinkableInput nativeLinkableInput =
          NativeLinkables.getTransitiveNativeLinkableInput(
              targetGraph,
              cxxPlatform,
              params.getDeps(),
              Linker.LinkableDepType.SHARED,
              Predicates.or(
                  Predicates.instanceOf(NativeLinkable.class),
                  Predicates.instanceOf(NdkLibrary.class)),
              /* reverse */ true);

      // We add any dependencies from the native linkable input to this rule, even though
      // it technically should be added to the top-level rule.
      deps.addAll(pathResolver.filterBuildRuleInputs(nativeLinkableInput.getInputs()));

      // Add in the transitive native linkable flags contributed by C/C++ library rules into the
      // NDK build.
      String localLdflags = Joiner.on(' ').join(escapeForMakefile(nativeLinkableInput.getArgs()));

      // Write the relevant lines to the generated makefile.
      if (!localCflags.isEmpty() || !localLdflags.isEmpty()) {
        NdkCxxPlatforms.TargetCpuType targetCpuType = entry.getKey();
        String targetArchAbi = getTargetArchAbi(targetCpuType);

        outputLinesBuilder.add(String.format("ifeq ($(TARGET_ARCH_ABI),%s)", targetArchAbi));
        if (!localCflags.isEmpty()) {
          outputLinesBuilder.add("BUCK_DEP_CFLAGS=" + localCflags);
        }
        if (!localLdflags.isEmpty()) {
          outputLinesBuilder.add("BUCK_DEP_LDFLAGS=" + localLdflags);
        }
        outputLinesBuilder.add("endif");
        outputLinesBuilder.add("");
      }
    }

    // GCC-only magic that rewrites non-deterministic parts of builds
    String ndksubst = NdkCxxPlatforms.ANDROID_NDK_ROOT;

    outputLinesBuilder.addAll(
        ImmutableList.copyOf(new String[] {
              // We're evaluated once per architecture, but want to add the cflags only once.
              "ifeq ($(BUCK_ALREADY_HOOKED_CFLAGS),)",
              "BUCK_ALREADY_HOOKED_CFLAGS := 1",
              // Only GCC supports -fdebug-prefix-map
              "ifeq ($(filter clang%,$(NDK_TOOLCHAIN_VERSION)),)",
              // Replace absolute paths with machine-relative ones.
              "NDK_APP_CFLAGS += -fdebug-prefix-map=$(NDK_ROOT)/=" + ndksubst + "/",
              "NDK_APP_CFLAGS += -fdebug-prefix-map=$(abspath $(BUCK_PROJECT_DIR))/=./",
              // Replace paths relative to the build rule with paths relative to the
              // repository root.
              "NDK_APP_CFLAGS += -fdebug-prefix-map=$(BUCK_PROJECT_DIR)/=./",
              "NDK_APP_CFLAGS += -fdebug-prefix-map=./=" +
              ".$(subst $(abspath $(BUCK_PROJECT_DIR)),,$(abspath $(CURDIR)))/",
              "NDK_APP_CFLAGS += -fno-record-gcc-switches",
              "ifeq ($(filter 4.6,$(TOOLCHAIN_VERSION)),)",
              // Do not let header canonicalization undo the work we just did above.  Note that GCC
              // 4.6 doesn't support this option, but that's okay, because it doesn't canonicalize
              // headers either.
              "NDK_APP_CPPFLAGS += -fno-canonical-system-headers",
              // If we include the -fdebug-prefix-map in the switches, the "from"-parts of which
              // contain machine-specific paths, we lose determinism.  GCC 4.6 didn't include
              // detailed command line argument information anyway.
              "NDK_APP_CFLAGS += -gno-record-gcc-switches",
              "endif", // !GCC 4.6
              "endif", // !clang

              // Rewrite NDK module paths to import managed modules by relative path instead of by
              // absolute path, but only for modules under the project root.
              "BUCK_SAVED_IMPORTS := $(__ndk_import_dirs)",
              "__ndk_import_dirs :=",
              "$(foreach __dir,$(BUCK_SAVED_IMPORTS),\\",
              "$(call import-add-path-optional,\\",
              "$(if $(filter $(abspath $(BUCK_PROJECT_DIR))%,$(__dir)),\\",
              "$(BUCK_PROJECT_DIR)$(patsubst $(abspath $(BUCK_PROJECT_DIR))%,%,$(__dir)),\\",
              "$(__dir))))",
              "endif", // !already hooked
              // Now add a toolchain directory to replace.  GCC's debug path replacement evaluates
              // candidate replaces last-first (because it internally pushes them all onto a stack
              // and scans the stack first-match-wins), so only add them after the more
              // generic paths.
              "NDK_APP_CFLAGS += -fdebug-prefix-map=$(TOOLCHAIN_PREBUILT_ROOT)/=" +
              "@ANDROID_NDK_ROOT@/toolchains/$(TOOLCHAIN_NAME)/prebuilt/@BUILD_HOST@/",
            }));

    outputLinesBuilder.add("include Android.mk");

    BuildTarget makefileTarget = BuildTarget
        .builder(params.getBuildTarget())
        .addFlavors(MAKEFILE_FLAVOR)
        .build();
    BuildRuleParams makefileParams = params.copyWithChanges(
        makefileTarget,
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
    final Path makefilePath = getGeneratedMakefilePath(params.getBuildTarget());
    final String contents = Joiner.on(System.lineSeparator()).join(outputLinesBuilder.build());

    return new Pair<BuildRule, Iterable<BuildRule>>(
        new WriteFile(makefileParams, pathResolver, contents, makefilePath),
        deps.build());
  }

  @VisibleForTesting
  protected ImmutableSortedSet<SourcePath> findSources(
      final ProjectFilesystem filesystem,
      final Path buildRulePath) {
    final ImmutableSortedSet.Builder<SourcePath> srcs = ImmutableSortedSet.naturalOrder();

    try {
      final Path rootDirectory = filesystem.resolve(buildRulePath);
      Files.walkFileTree(
          rootDirectory,
          EnumSet.of(FileVisitOption.FOLLOW_LINKS),
          /* maxDepth */ Integer.MAX_VALUE,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              if (EXTENSIONS_REGEX.matcher(file.toString()).matches()) {
                srcs.add(
                    new PathSourcePath(
                        filesystem,
                        buildRulePath.resolve(rootDirectory.relativize(file))));
              }

              return super.visitFile(file, attrs);
            }
          });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return srcs.build();
  }

  @Override
  public <A extends Arg> NdkLibrary createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    Pair<BuildRule, Iterable<BuildRule>> makefilePair =
        generateMakefile(targetGraph, params, resolver);
    resolver.addToIndex(makefilePair.getFirst());
    return new NdkLibrary(
        params.appendExtraDeps(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .add(makefilePair.getFirst())
                .addAll(makefilePair.getSecond())
                .build()),
        new SourcePathResolver(resolver),
        getGeneratedMakefilePath(params.getBuildTarget()),
        findSources(params.getProjectFilesystem(), params.getBuildTarget().getBasePath()),
        args.flags.get(),
        args.isAsset.or(false),
        ndkVersion,
        MACRO_HANDLER.getExpander(
            params.getBuildTarget(),
            resolver,
            params.getProjectFilesystem()));
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableList<String>> flags;
    public Optional<Boolean> isAsset;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

}

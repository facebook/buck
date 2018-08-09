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

import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.AndroidNdkConstants;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxHeaders;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceTypes;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.EnvironmentVariableMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.string.MoreStrings;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class NdkLibraryDescription implements DescriptionWithTargetGraph<NdkLibraryDescriptionArg> {

  private static final Pattern EXTENSIONS_REGEX =
      Pattern.compile(
          ".*\\."
              + MoreStrings.regexPatternForAny("mk", "h", "hpp", "c", "cpp", "cc", "cxx")
              + "$");

  public static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.of("env", new EnvironmentVariableMacroExpander(Platform.detect())));

  @Override
  public Class<NdkLibraryDescriptionArg> getConstructorArgType() {
    return NdkLibraryDescriptionArg.class;
  }

  private Iterable<String> escapeForMakefile(
      ProjectFilesystem filesystem, boolean escapeInQuotes, Iterable<String> args) {
    ImmutableList.Builder<String> escapedArgs = ImmutableList.builder();

    for (String arg : args) {
      String escapedArg = arg;

      // The ndk-build makefiles make heavy use of the "eval" function to propagate variables,
      // which means we need to perform additional makefile escaping for *every* "eval" that
      // gets used.  Turns out there are three "evals", so we escape a total of four times
      // including the initial escaping.  Since the makefiles eventually hand-off these values
      // to the shell, we first perform bash escaping.
      //
      // Additionally to that Android NDK 16 has a fix that changes the escaping logic for some of
      // the flags, but not for all of them. This means some flags needs to be escaped with the old
      // logic (4 escapes) while other flags must be escaped using a simpler approach.
      // Github issue with the fix: https://github.com/android-ndk/ndk/issues/161

      escapedArg = Escaper.escapeAsShellString(escapedArg);

      if (escapeInQuotes) {
        escapedArg = Escaper.escapeWithQuotesAsMakefileValueString(escapedArg);
      } else {
        for (int i = 0; i < 4; i++) {
          escapedArg = Escaper.escapeAsMakefileValueString(escapedArg);
        }
      }

      // We run ndk-build from the root of the NDK, so fixup paths that use the relative path to
      // the buck out directory.
      if (arg.startsWith(filesystem.getBuckPaths().getBuckOut().toString())) {
        escapedArg = "$(BUCK_PROJECT_DIR)/" + escapedArg;
      }

      escapedArgs.add(escapedArg);
    }

    return escapedArgs.build();
  }

  private String getTargetArchAbi(TargetCpuType cpuType) {
    switch (cpuType) {
      case ARM:
        return "armeabi";
      case ARMV7:
        return "armeabi-v7a";
      case ARM64:
        return "arm64-v8a";
      case X86:
        return "x86";
      case X86_64:
        return "x86_64";
      case MIPS:
        return "mips";
      default:
        throw new IllegalStateException();
    }
  }

  @VisibleForTesting
  protected static Path getGeneratedMakefilePath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getGenPath(filesystem, target, "Android.%s.mk");
  }

  /**
   * @return a {@link BuildRule} which generates a Android.mk which pulls in the local Android.mk
   *     file and also appends relevant preprocessor and linker flags to use C/C++ library deps.
   */
  private Pair<String, Iterable<BuildRule>> generateMakefile(
      ToolchainProvider toolchainProvider,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    ImmutableList.Builder<String> outputLinesBuilder = ImmutableList.builder();
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();

    NdkCxxPlatformsProvider ndkCxxPlatformsProvider =
        toolchainProvider.getByName(
            NdkCxxPlatformsProvider.DEFAULT_NAME, NdkCxxPlatformsProvider.class);
    AndroidNdk androidNdk = toolchainProvider.getByName(AndroidNdk.DEFAULT_NAME, AndroidNdk.class);

    for (Map.Entry<TargetCpuType, NdkCxxPlatform> entry :
        ndkCxxPlatformsProvider.getNdkCxxPlatforms().entrySet()) {
      CxxPlatform cxxPlatform = entry.getValue().getCxxPlatform();

      // Collect the preprocessor input for all C/C++ library deps.  We search *through* other
      // NDK library rules.
      CxxPreprocessorInput cxxPreprocessorInput =
          CxxPreprocessorInput.concat(
              CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                  cxxPlatform, graphBuilder, params.getBuildDeps(), NdkLibrary.class::isInstance));

      // We add any dependencies from the C/C++ preprocessor input to this rule, even though
      // it technically should be added to the top-level rule.
      deps.addAll(cxxPreprocessorInput.getDeps(graphBuilder, ruleFinder));

      // Add in the transitive preprocessor flags contributed by C/C++ library rules into the
      // NDK build.
      ImmutableList.Builder<String> ppFlags = ImmutableList.builder();
      ppFlags.addAll(
          Arg.stringify(
              cxxPreprocessorInput.getPreprocessorFlags().get(CxxSource.Type.C), pathResolver));
      Preprocessor preprocessor =
          CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.C).resolve(graphBuilder);
      ppFlags.addAll(
          CxxHeaders.getArgs(
              cxxPreprocessorInput.getIncludes(), pathResolver, Optional.empty(), preprocessor));
      String localCflags =
          Joiner.on(' ')
              .join(
                  escapeForMakefile(
                      projectFilesystem,
                      androidNdk.shouldEscapeCFlagsInDoubleQuotes(),
                      ppFlags.build()));

      // Collect the native linkable input for all C/C++ library deps.  We search *through* other
      // NDK library rules.
      NativeLinkableInput nativeLinkableInput =
          NativeLinkables.getTransitiveNativeLinkableInput(
              cxxPlatform,
              graphBuilder,
              params.getBuildDeps(),
              Linker.LinkableDepType.SHARED,
              r -> r instanceof NdkLibrary ? Optional.of(r.getBuildDeps()) : Optional.empty());

      // We add any dependencies from the native linkable input to this rule, even though
      // it technically should be added to the top-level rule.
      deps.addAll(
          nativeLinkableInput
              .getArgs()
              .stream()
              .flatMap(arg -> BuildableSupport.getDeps(arg, ruleFinder))
              .iterator());

      // Add in the transitive native linkable flags contributed by C/C++ library rules into the
      // NDK build.
      String localLdflags =
          Joiner.on(' ')
              .join(
                  escapeForMakefile(
                      projectFilesystem,
                      false,
                      Arg.stringify(nativeLinkableInput.getArgs(), pathResolver)));

      // Write the relevant lines to the generated makefile.
      if (!localCflags.isEmpty() || !localLdflags.isEmpty()) {
        TargetCpuType targetCpuType = entry.getKey();
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
    String ndksubst = AndroidNdkConstants.ANDROID_NDK_ROOT;

    outputLinesBuilder.addAll(
        ImmutableList.copyOf(
            new String[] {
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
              "NDK_APP_CFLAGS += -fdebug-prefix-map=./="
                  + ".$(subst $(abspath $(BUCK_PROJECT_DIR)),,$(abspath $(CURDIR)))/",
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
              "NDK_APP_CFLAGS += -fdebug-prefix-map=$(TOOLCHAIN_PREBUILT_ROOT)/="
                  + "@ANDROID_NDK_ROOT@/toolchains/$(TOOLCHAIN_NAME)/prebuilt/@BUILD_HOST@/",
            }));

    outputLinesBuilder.add("include Android.mk");

    String contents = Joiner.on(System.lineSeparator()).join(outputLinesBuilder.build());

    return new Pair<String, Iterable<BuildRule>>(contents, deps.build());
  }

  @VisibleForTesting
  protected ImmutableSortedSet<SourcePath> findSources(
      ProjectFilesystem filesystem, Path buildRulePath) {
    ImmutableSortedSet.Builder<SourcePath> srcs = ImmutableSortedSet.naturalOrder();

    try {
      Path rootDirectory = filesystem.resolve(buildRulePath);
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
                    PathSourcePath.of(
                        filesystem, buildRulePath.resolve(rootDirectory.relativize(file))));
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
  public NdkLibrary createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      NdkLibraryDescriptionArg args) {
    ToolchainProvider toolchainProvider = context.getToolchainProvider();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    Pair<String, Iterable<BuildRule>> makefilePair =
        generateMakefile(
            toolchainProvider, projectFilesystem, params, context.getActionGraphBuilder());

    ImmutableSortedSet<SourcePath> sources;
    if (!args.getSrcs().isEmpty()) {
      sources = args.getSrcs();
    } else {
      sources = findSources(projectFilesystem, buildTarget.getBasePath());
    }
    AndroidNdk androidNdk = toolchainProvider.getByName(AndroidNdk.DEFAULT_NAME, AndroidNdk.class);
    return new NdkLibrary(
        buildTarget,
        projectFilesystem,
        toolchainProvider.getByName(AndroidNdk.DEFAULT_NAME, AndroidNdk.class),
        params.copyAppendingExtraDeps(
            ImmutableSortedSet.<BuildRule>naturalOrder().addAll(makefilePair.getSecond()).build()),
        getGeneratedMakefilePath(buildTarget, projectFilesystem),
        makefilePair.getFirst(),
        sources,
        args.getFlags(),
        args.getIsAsset(),
        androidNdk.getNdkVersion(),
        MACRO_HANDLER.getExpander(
            buildTarget, context.getCellPathResolver(), context.getActionGraphBuilder()));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractNdkLibraryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs {
    ImmutableList<String> getFlags();

    @Value.Default
    default boolean getIsAsset() {
      return false;
    }
  }
}

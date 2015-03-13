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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.macros.EnvironmentVariableMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
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
import com.google.common.collect.ImmutableCollection;
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

  private static final BuildRuleType MAKEFILE_TYPE =
      BuildRuleType.of("ndk_library_makefile");
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
  private final ImmutableMap<AndroidBinary.TargetCpuType, NdkCxxPlatform> cxxPlatforms;

  public NdkLibraryDescription(
      Optional<String> ndkVersion,
      ImmutableMap<AndroidBinary.TargetCpuType, NdkCxxPlatform> cxxPlatforms) {
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
      escapedArg = Escaper.escapeAsBashString(escapedArg);
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

  private String getTargetArchAbi(AndroidBinary.TargetCpuType cpuType) {
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
  private BuildRule generateMakefile(
      final BuildRuleParams params,
      BuildRuleResolver resolver) {

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableList.Builder<String> outputLinesBuilder = ImmutableList.builder();
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();

    for (Map.Entry<AndroidBinary.TargetCpuType, NdkCxxPlatform> entry : cxxPlatforms.entrySet()) {
      CxxPlatform cxxPlatform = entry.getValue().getCxxPlatform();

      CxxPreprocessorInput cxxPreprocessorInput;
      try {
        // Collect the preprocessor input for all C/C++ library deps.  We search *through* other
        // NDK library rules.
        cxxPreprocessorInput =
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                cxxPlatform,
                params.getDeps(),
                Predicates.or(
                    Predicates.instanceOf(CxxPreprocessorDep.class),
                    Predicates.instanceOf(NdkLibrary.class)));
      } catch (CxxPreprocessorInput.ConflictingHeadersException e) {
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
        AndroidBinary.TargetCpuType targetCpuType = entry.getKey();
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

    outputLinesBuilder.add("include Android.mk");

    BuildTarget makefileTarget = BuildTarget
        .builder(params.getBuildTarget())
        .addFlavors(MAKEFILE_FLAVOR)
        .build();
    BuildRuleParams makefileParams = params.copyWithChanges(
        MAKEFILE_TYPE,
        makefileTarget,
        Suppliers.ofInstance(deps.build()),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
    final Path makefilePath = getGeneratedMakefilePath(params.getBuildTarget());
    final String contents = Joiner.on(System.lineSeparator()).join(outputLinesBuilder.build());

    return new AbstractBuildRule(makefileParams, pathResolver) {

      @Override
      protected ImmutableCollection<Path> getInputsToCompareToOutput() {
        return ImmutableList.of();
      }

      @Override
      protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
        return builder
            .setReflectively("contents", contents)
            .setReflectively("output", makefilePath.toString());
      }

      @Override
      public ImmutableList<Step> getBuildSteps(
          BuildContext context,
          BuildableContext buildableContext) {
        buildableContext.recordArtifact(makefilePath);
        return ImmutableList.of(
            new MkdirStep(makefilePath.getParent()),
            new WriteFileStep(contents, makefilePath));
      }

      @Override
      public Path getPathToOutputFile() {
        return makefilePath;
      }

    };

  }

  @Override
  public <A extends Arg> NdkLibrary createBuildRule(
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    BuildRule makefile = generateMakefile(params, resolver);
    resolver.addToIndex(makefile);

    final ImmutableSortedSet.Builder<SourcePath> srcs = ImmutableSortedSet.naturalOrder();

    try {
      final Path buildRulePath = params.getBuildTarget().getBasePath();
      final Path rootDirectory = params.getProjectFilesystem().resolve(buildRulePath);
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
                        params.getProjectFilesystem(),
                        buildRulePath.resolve(rootDirectory.relativize(file))));
              }

              return super.visitFile(file, attrs);
            }
          });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return new NdkLibrary(
        params.copyWithExtraDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(params.getExtraDeps())
                    .add(makefile)
                    .build())),
        new SourcePathResolver(resolver),
        getGeneratedMakefilePath(params.getBuildTarget()),
        srcs.build(),
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

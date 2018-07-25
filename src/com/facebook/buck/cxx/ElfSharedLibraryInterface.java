/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.elf.ElfDynamicSection;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.Memoizer;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Function;

/** Build a shared library interface from an ELF shared library. */
abstract class ElfSharedLibraryInterface extends AbstractBuildRule
    implements HasDeclaredAndExtraDeps, SupportsInputBasedRuleKey {

  @AddToRuleKey private final Tool objcopy;

  @AddToRuleKey private final String libName;

  @AddToRuleKey private final boolean removeUndefinedSymbols;

  private final Function<SourcePathRuleFinder, SortedSet<BuildRule>> computeDeclaredDeps;
  private final Memoizer<SortedSet<BuildRule>> declaredDeps = new Memoizer<>();

  private SourcePathRuleFinder ruleFinder;

  private ElfSharedLibraryInterface(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Function<SourcePathRuleFinder, SortedSet<BuildRule>> computeDeclaredDeps,
      Tool objcopy,
      String libName,
      boolean removeUndefinedSymbols) {
    super(buildTarget, projectFilesystem);
    this.ruleFinder = ruleFinder;
    this.computeDeclaredDeps = computeDeclaredDeps;
    this.objcopy = objcopy;
    this.libName = libName;
    this.removeUndefinedSymbols = removeUndefinedSymbols;
  }

  /** @return a {@link ElfSharedLibraryInterface} distilled from an existing shared library. */
  public static ElfSharedLibraryInterface from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Tool objcopy,
      SourcePath input,
      boolean removeUndefinedSymbols) {
    return new ElfSharedLibraryInterface(
        target,
        projectFilesystem,
        ruleFinder,
        (ruleFinderInner) ->
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(BuildableSupport.getDepsCollection(objcopy, ruleFinderInner))
                .addAll(ruleFinderInner.filterBuildRuleInputs(input))
                .build(),
        objcopy,
        resolver.getRelativePath(input).getFileName().toString(),
        removeUndefinedSymbols) {

      @AddToRuleKey SourcePath eInput = input;

      @Override
      protected Pair<ProjectFilesystem, Path> getInput(BuildContext context, Builder<Step> steps) {
        return new Pair<>(
            context.getSourcePathResolver().getFilesystem(input),
            context.getSourcePathResolver().getRelativePath(input));
      }
    };
  }

  /**
   * @return a {@link ElfSharedLibraryInterface} built for the library represented by {@link
   *     NativeLinkTarget}.
   */
  public static ElfSharedLibraryInterface from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Tool objcopy,
      String libName,
      Linker linker,
      ImmutableList<Arg> args,
      boolean removeUndefinedSymbols) {

    return new ElfSharedLibraryInterface(
        target,
        projectFilesystem,
        ruleFinder,
        (ruleFinderInner) ->
            RichStream.from(args)
                .flatMap(arg -> BuildableSupport.getDeps(arg, ruleFinderInner))
                .concat(BuildableSupport.getDeps(linker, ruleFinderInner))
                .concat(BuildableSupport.getDeps(objcopy, ruleFinderInner))
                .toImmutableSortedSet(Ordering.natural()),
        objcopy,
        libName,
        removeUndefinedSymbols) {

      @AddToRuleKey ImmutableList<Arg> rArgs = args;

      // Add steps to link the `NativeLinkTarget` as a dep-free shared library (which should be a
      // lot faster than linking with deps), which we'll use to distill the shared library
      // interface.
      @Override
      protected Pair<ProjectFilesystem, Path> getInput(BuildContext context, Builder<Step> steps) {
        Path argFilePath =
            getProjectFilesystem()
                .getRootPath()
                .resolve(getScratchDir())
                .resolve(
                    String.format("%s.argsfile", getBuildTarget().getShortNameAndFlavorPostfix()));
        Path fileListPath =
            getProjectFilesystem()
                .getRootPath()
                .resolve(getScratchDir())
                .resolve(
                    String.format(
                        "%s__filelist.txt", getBuildTarget().getShortNameAndFlavorPostfix()));
        Path output = getScratchDir().resolve(libName);
        steps
            .add(
                RmStep.of(
                    BuildCellRelativePath.fromCellRelativePath(
                        context.getBuildCellRootPath(), getProjectFilesystem(), argFilePath)))
            .add(
                RmStep.of(
                    BuildCellRelativePath.fromCellRelativePath(
                        context.getBuildCellRootPath(), getProjectFilesystem(), fileListPath)))
            .addAll(
                CxxPrepareForLinkStep.create(
                    argFilePath,
                    fileListPath,
                    linker.fileList(fileListPath),
                    output,
                    args,
                    linker,
                    getBuildTarget().getCellPath(),
                    context.getSourcePathResolver()))
            .add(
                new CxxLinkStep(
                    getProjectFilesystem().getRootPath(),
                    linker.getEnvironment(context.getSourcePathResolver()),
                    linker.getCommandPrefix(context.getSourcePathResolver()),
                    argFilePath,
                    getProjectFilesystem().getRootPath().resolve(getScratchDir())));
        return new Pair<>(getProjectFilesystem(), output);
      }
    };
  }

  private Path getOutputDir() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  private Path getOutput() {
    return getOutputDir().resolve(libName);
  }

  protected Path getScratchDir() {
    return BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  // We only care about sections relevant to dynamic linking.
  private ImmutableSet<String> getSections() {
    ImmutableSet.Builder<String> sections = ImmutableSet.builder();
    sections.add(".dynamic", ".dynsym", ".dynstr", ".gnu.version", ".gnu.version_d");
    // The `.gnu.version_r` contains version information about undefined symbols, and so is only
    // relevant if we're not removing undefined symbols.
    if (!removeUndefinedSymbols) {
      sections.add(".gnu.version_r");
    }
    return sections.build();
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return getDeclaredDeps();
  }

  @Override
  public SortedSet<BuildRule> getDeclaredDeps() {
    return declaredDeps.get(() -> computeDeclaredDeps.apply(ruleFinder));
  }

  @Override
  public SortedSet<BuildRule> deprecatedGetExtraDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getTargetGraphOnlyDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Path output = getOutput();
    Path outputScratch = getScratchDir().resolve(libName + ".scratch");
    buildableContext.recordArtifact(output);
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), getOutputDir())));
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), getScratchDir())));
    Pair<ProjectFilesystem, Path> input = getInput(context, steps);
    steps.add(
        new ElfExtractSectionsStep(
            objcopy.getCommandPrefix(context.getSourcePathResolver()),
            getSections(),
            input.getFirst(),
            input.getSecond(),
            getProjectFilesystem(),
            outputScratch),
        ElfSymbolTableScrubberStep.of(
            getProjectFilesystem(),
            outputScratch,
            /* section */ ".dynsym",
            /* versymSection */ Optional.of(".gnu.version"),
            /* allowMissing */ false,
            /* scrubUndefinedSymbols */ removeUndefinedSymbols),
        ElfSymbolTableScrubberStep.of(
            getProjectFilesystem(),
            outputScratch,
            /* section */ ".symtab",
            /* versymSection */ Optional.empty(),
            /* allowMissing */ true,
            /* scrubUndefinedSymbols */ true),
        ElfDynamicSectionScrubberStep.of(
            getProjectFilesystem(),
            outputScratch,
            // When scrubbing undefined symbols, drop the `DT_NEEDED` tags from the whitelist, as
            // these leak information about undefined references in the shared library.
            /* whitelistedTags */ removeUndefinedSymbols
                ? ImmutableSet.of(ElfDynamicSection.DTag.DT_SONAME)
                : ImmutableSet.of(
                    ElfDynamicSection.DTag.DT_NEEDED, ElfDynamicSection.DTag.DT_SONAME),
            /* removeScrubbedTags */ removeUndefinedSymbols),
        ElfScrubFileHeaderStep.of(getProjectFilesystem(), outputScratch));
    // If we're removing undefined symbols, rewrite the dynamic string table so that strings for
    // undefined symbol names are removed.
    if (removeUndefinedSymbols) {
      steps.add(ElfRewriteDynStrSectionStep.of(getProjectFilesystem(), outputScratch));
    }
    steps.add(
        // objcopy doesn't like the section-address shuffling chicanery we're doing in
        // the ElfCompactSectionsStep, since the new addresses may not jive with the current
        // segment locations.  So kill the segments (program headers) in the scratch file
        // prior to compacting sections, and _again_ in the interface .so file.
        ElfClearProgramHeadersStep.of(getProjectFilesystem(), outputScratch),
        ElfCompactSectionsStep.of(
            getBuildTarget(),
            objcopy.getCommandPrefix(context.getSourcePathResolver()),
            getProjectFilesystem(),
            outputScratch,
            getProjectFilesystem(),
            output),
        ElfClearProgramHeadersStep.of(getProjectFilesystem(), output));
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getOutputDir().resolve(libName));
  }

  @Override
  public void updateBuildRuleResolver(
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver) {
    this.ruleFinder = ruleFinder;
  }

  /**
   * @return add any necessary steps to generate the input shared library we'll use to generate the
   *     interface and return it's path.
   */
  protected abstract Pair<ProjectFilesystem, Path> getInput(
      BuildContext context, ImmutableList.Builder<Step> steps);
}

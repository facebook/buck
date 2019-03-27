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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.ElfSharedLibraryInterface.AbstractBuildable;
import com.facebook.buck.cxx.toolchain.elf.ElfDynamicSection;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

/** Build a shared library interface from an ELF shared library. */
class ElfSharedLibraryInterface<T extends AbstractBuildable> extends ModernBuildRule<T> {

  private ElfSharedLibraryInterface(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      T buildable) {
    super(buildTarget, projectFilesystem, ruleFinder, buildable);
  }

  /** @return a {@link ElfSharedLibraryInterface} distilled from an existing shared library. */
  public static ElfSharedLibraryInterface<ExistingBasedElfSharedLibraryImpl> from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Tool objcopy,
      SourcePath input,
      boolean removeUndefinedSymbols) {
    String libName = resolver.getRelativePath(input).getFileName().toString();
    return new ElfSharedLibraryInterface<>(
        target,
        projectFilesystem,
        ruleFinder,
        new ExistingBasedElfSharedLibraryImpl(
            target, objcopy, libName, removeUndefinedSymbols, input));
  }

  /**
   * @return a {@link ElfSharedLibraryInterface} built for the library represented by {@link
   *     NativeLinkTarget}.
   */
  public static ElfSharedLibraryInterface<LinkerBasedElfSharedLibraryImpl> from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Tool objcopy,
      String libName,
      Linker linker,
      ImmutableList<Arg> args,
      boolean removeUndefinedSymbols) {
    return new ElfSharedLibraryInterface<>(
        target,
        projectFilesystem,
        ruleFinder,
        new LinkerBasedElfSharedLibraryImpl(
            target, objcopy, libName, removeUndefinedSymbols, linker, args));
  }

  /**
   * Internal ElfSharedLibrary specific abstract class with general implementation for Buildable
   * interface
   */
  abstract static class AbstractBuildable implements Buildable {

    @AddToRuleKey protected final BuildTarget buildTarget;
    @AddToRuleKey private final Tool objcopy;
    @AddToRuleKey private final OutputPath outputPath;
    @AddToRuleKey protected final String libName;
    @AddToRuleKey private final boolean removeUndefinedSymbols;

    private AbstractBuildable(
        BuildTarget buildTarget, Tool objcopy, String libName, boolean removeUndefinedSymbols) {
      this.buildTarget = buildTarget;
      this.objcopy = objcopy;
      this.libName = libName;
      this.outputPath = new OutputPath(libName);
      this.removeUndefinedSymbols = removeUndefinedSymbols;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      Path output = outputPathResolver.resolvePath(outputPath);
      Path outputDir = outputPathResolver.getRootPath();
      Path outputScratch = outputPathResolver.resolvePath(new OutputPath(libName + ".scratch"));
      ImmutableList.Builder<Step> steps = ImmutableList.builder();

      SourcePathResolver sourcePathResolver = buildContext.getSourcePathResolver();
      ImmutableList<String> commandPrefix = objcopy.getCommandPrefix(sourcePathResolver);
      Pair<ProjectFilesystem, Path> input = getInput(buildContext, filesystem, outputDir, steps);
      steps.add(
          new ElfExtractSectionsStep(
              commandPrefix,
              getSections(),
              input.getFirst(),
              input.getSecond(),
              filesystem,
              outputScratch),
          ElfSymbolTableScrubberStep.of(
              filesystem,
              outputScratch,
              /* section */ ".dynsym",
              /* versymSection */ Optional.of(".gnu.version"),
              /* allowMissing */ false,
              /* scrubUndefinedSymbols */ removeUndefinedSymbols),
          ElfSymbolTableScrubberStep.of(
              filesystem,
              outputScratch,
              /* section */ ".symtab",
              /* versymSection */ Optional.empty(),
              /* allowMissing */ true,
              /* scrubUndefinedSymbols */ true),
          ElfDynamicSectionScrubberStep.of(
              filesystem,
              outputScratch,
              // When scrubbing undefined symbols, drop the `DT_NEEDED` tags from the whitelist,
              // as these leak information about undefined references in the shared library.
              /* whitelistedTags */ removeUndefinedSymbols
                  ? ImmutableSet.of(ElfDynamicSection.DTag.DT_SONAME)
                  : ImmutableSet.of(
                      ElfDynamicSection.DTag.DT_NEEDED, ElfDynamicSection.DTag.DT_SONAME),
              /* removeScrubbedTags */ removeUndefinedSymbols),
          ElfScrubFileHeaderStep.of(filesystem, outputScratch));
      // If we're removing undefined symbols, rewrite the dynamic string table so that strings for
      // undefined symbol names are removed.
      if (removeUndefinedSymbols) {
        steps.add(ElfRewriteDynStrSectionStep.of(filesystem, outputScratch));
      }
      steps.add(
          // objcopy doesn't like the section-address shuffling chicanery we're doing in
          // the ElfCompactSectionsStep, since the new addresses may not jive with the current
          // segment locations.  So kill the segments (program headers) in the scratch file
          // prior to compacting sections, and _again_ in the interface .so file.
          ElfClearProgramHeadersStep.of(filesystem, outputScratch),
          ElfCompactSectionsStep.of(
              buildTarget, commandPrefix, filesystem, outputScratch, filesystem, output),
          ElfClearProgramHeadersStep.of(filesystem, output));
      return steps.build();
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

    /**
     * @return add any necessary steps to generate the input shared library we'll use to generate
     *     the interface and return it's path.
     */
    protected abstract Pair<ProjectFilesystem, Path> getInput(
        BuildContext context, ProjectFilesystem filesystem, Path outputPath, Builder<Step> steps);
  }

  private static class ExistingBasedElfSharedLibraryImpl extends AbstractBuildable {

    @AddToRuleKey private final SourcePath input;

    ExistingBasedElfSharedLibraryImpl(
        BuildTarget buildTarget,
        Tool objcopy,
        String libName,
        boolean removeUndefinedSymbols,
        SourcePath input) {
      super(buildTarget, objcopy, libName, removeUndefinedSymbols);
      this.input = input;
    }

    @Override
    protected Pair<ProjectFilesystem, Path> getInput(
        BuildContext context, ProjectFilesystem filesystem, Path outputPath, Builder<Step> steps) {
      SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
      return new Pair<>(
          sourcePathResolver.getFilesystem(input), sourcePathResolver.getRelativePath(input));
    }
  }

  private static class LinkerBasedElfSharedLibraryImpl extends AbstractBuildable {

    @AddToRuleKey private final Linker linker;
    @AddToRuleKey private final ImmutableList<Arg> args;

    LinkerBasedElfSharedLibraryImpl(
        BuildTarget buildTarget,
        Tool objcopy,
        String libName,
        boolean removeUndefinedSymbols,
        Linker linker,
        ImmutableList<Arg> args) {
      super(buildTarget, objcopy, libName, removeUndefinedSymbols);
      this.linker = linker;
      this.args = args;
    }

    @Override
    protected Pair<ProjectFilesystem, Path> getInput(
        BuildContext context, ProjectFilesystem filesystem, Path outputPath, Builder<Step> steps) {
      String shortNameAndFlavorPostfix = buildTarget.getShortNameAndFlavorPostfix();
      Path outputDirPath = filesystem.getRootPath().resolve(outputPath);

      Path argFilePath =
          outputDirPath.resolve(String.format("%s.argsfile", shortNameAndFlavorPostfix));
      Path fileListPath =
          outputDirPath.resolve(String.format("%s__filelist.txt", shortNameAndFlavorPostfix));
      Path output = outputPath.resolve(libName);
      SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
      steps
          .addAll(
              CxxPrepareForLinkStep.create(
                  argFilePath,
                  fileListPath,
                  linker.fileList(fileListPath),
                  output,
                  args,
                  linker,
                  buildTarget.getCellPath(),
                  sourcePathResolver))
          .add(
              new CxxLinkStep(
                  filesystem.getRootPath(),
                  linker.getEnvironment(sourcePathResolver),
                  linker.getCommandPrefix(sourcePathResolver),
                  argFilePath,
                  outputDirPath));
      return new Pair<>(filesystem, output);
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    AbstractBuildable buildable = getBuildable();
    return getSourcePath(buildable.outputPath);
  }
}

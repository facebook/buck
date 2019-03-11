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

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.features.go.GoListStep.ListType;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.step.fs.TouchStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class GoCompile extends AbstractBuildRuleWithDeclaredAndExtraDeps {
  @AddToRuleKey private final Tool compiler;
  @AddToRuleKey private final Tool assembler;
  @AddToRuleKey private final Tool packer;

  @AddToRuleKey(stringify = true)
  private final Path packageName;

  @AddToRuleKey private final ImmutableSet<SourcePath> srcs;
  @AddToRuleKey private final ImmutableSet<SourcePath> generatedSrcs;
  @AddToRuleKey private final ImmutableList<String> compilerFlags;
  @AddToRuleKey private final ImmutableList<String> assemblerFlags;
  @AddToRuleKey private final ImmutableList<SourcePath> extraAsmOutputs;
  @AddToRuleKey private final GoPlatform platform;
  @AddToRuleKey private final boolean gensymabis;

  // TODO(mikekap): Make these part of the rule key.
  private final ImmutableList<Path> assemblerIncludeDirs;
  private final ImmutableMap<Path, Path> importPathMap;

  private final SymlinkTree symlinkTree;
  private final Path output;
  private final List<ListType> goListTypes;

  public GoCompile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SymlinkTree symlinkTree,
      Path packageName,
      ImmutableMap<Path, Path> importPathMap,
      ImmutableSet<SourcePath> srcs,
      ImmutableSet<SourcePath> generatedSrcs,
      ImmutableList<String> compilerFlags,
      ImmutableList<String> assemblerFlags,
      GoPlatform platform,
      boolean gensymabis,
      ImmutableList<SourcePath> extraAsmOutputs,
      List<ListType> goListTypes) {
    super(buildTarget, projectFilesystem, params);
    this.importPathMap = importPathMap;
    this.srcs = srcs;
    this.generatedSrcs = generatedSrcs;
    this.symlinkTree = symlinkTree;
    this.packageName = packageName;
    this.compilerFlags = compilerFlags;
    this.compiler = platform.getCompiler();
    this.assemblerFlags = assemblerFlags;
    this.assemblerIncludeDirs = platform.getAssemblerIncludeDirs();
    this.assembler = platform.getAssembler();
    this.packer = platform.getPacker();
    this.platform = platform;
    this.gensymabis = gensymabis;
    this.output =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem(),
            getBuildTarget(),
            "%s/" + getBuildTarget().getShortName() + ".a");
    this.extraAsmOutputs = extraAsmOutputs;
    this.goListTypes = goListTypes;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    buildableContext.recordArtifact(output);

    List<Path> srcFiles = getSourceFiles(srcs, context);
    ImmutableMap<String, ImmutableList<Path>> groupedSrcs = getGroupedSrcs(srcFiles);
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Optional<Path> asmHeaderPath = Optional.empty();
    boolean allowExternalReferences =
        !getAsmSources(groupedSrcs).isEmpty() || !extraAsmOutputs.isEmpty();

    FilteredSourceFiles filteredAsmSrcs =
        new FilteredSourceFiles(
            getAsmSources(groupedSrcs), platform, Collections.singletonList(ListType.SFiles));
    steps.addAll(filteredAsmSrcs.getFilterSteps());

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));

    Path asmOutputDir =
        BuildTargetPaths.getScratchPath(
            getProjectFilesystem(),
            getBuildTarget(),
            "%s/" + getBuildTarget().getShortName() + "__asm_compile");

    Path asmIncludeDir =
        BuildTargetPaths.getScratchPath(
            getProjectFilesystem(),
            getBuildTarget(),
            "%s/" + getBuildTarget().getShortName() + "__asm_includes");

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), asmOutputDir)));

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), asmIncludeDir)));

    if (!getAsmSources(groupedSrcs).isEmpty()) {
      asmHeaderPath =
          Optional.of(
              BuildTargetPaths.getScratchPath(
                      getProjectFilesystem(),
                      getBuildTarget(),
                      "%s/" + getBuildTarget().getShortName() + "__asm_hdr")
                  .resolve("go_asm.h"));

      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  asmHeaderPath.get().getParent())));
    }

    SourcePathResolver resolver = context.getSourcePathResolver();
    if (getGoSources(groupedSrcs).isEmpty()) {
      steps.add(new TouchStep(getProjectFilesystem(), output));
    } else {
      Optional<Path> asmSymabisPath = Optional.empty();

      if (gensymabis && !getAsmSources(groupedSrcs).isEmpty()) {
        asmSymabisPath = Optional.of(asmOutputDir.resolve("symabis"));

        steps.add(new TouchStep(getProjectFilesystem(), asmHeaderPath.get()));
        steps.add(
            new GoAssembleStep(
                getProjectFilesystem().getRootPath(),
                assembler.getEnvironment(resolver),
                assembler.getCommandPrefix(resolver),
                ImmutableList.<String>builder().addAll(assemblerFlags).add("-gensymabis").build(),
                filteredAsmSrcs,
                ImmutableList.<Path>builder()
                    .addAll(assemblerIncludeDirs)
                    .add(asmHeaderPath.get().getParent())
                    .add(asmIncludeDir)
                    .build(),
                platform,
                asmSymabisPath.get()));
      }

      FilteredSourceFiles filteredCompileSrcs =
          new FilteredSourceFiles(
              getGoSources(groupedSrcs),
              getSourceFiles(generatedSrcs, context),
              platform,
              goListTypes);
      steps.addAll(filteredCompileSrcs.getFilterSteps());

      steps.add(
          new GoCompileStep(
              getProjectFilesystem().getRootPath(),
              compiler.getEnvironment(resolver),
              compiler.getCommandPrefix(resolver),
              compilerFlags,
              packageName,
              filteredCompileSrcs,
              filteredAsmSrcs,
              importPathMap,
              ImmutableList.of(symlinkTree.getRoot()),
              asmHeaderPath,
              allowExternalReferences,
              platform,
              asmSymabisPath,
              output));
    }

    ImmutableList.Builder<Path> asmOutputs = ImmutableList.builder();

    if (!getAsmSources(groupedSrcs).isEmpty()) {
      Path asmOutputPath = asmOutputDir.resolve(getBuildTarget().getShortName() + ".o");

      if (!getHeaderSources(groupedSrcs).isEmpty()) {
        // TODO(mikekap): Allow header-map style input.
        for (Path header :
            Stream.of(getHeaderSources(groupedSrcs), getAsmSources(groupedSrcs))
                .flatMap(ImmutableList::stream)
                .collect(ImmutableList.toImmutableList())) {
          steps.add(
              SymlinkFileStep.builder()
                  .setFilesystem(getProjectFilesystem())
                  .setExistingFile(header)
                  .setDesiredLink(asmIncludeDir.resolve(header.getFileName()))
                  .build());
        }
      }

      steps.add(
          new GoAssembleStep(
              getProjectFilesystem().getRootPath(),
              assembler.getEnvironment(resolver),
              assembler.getCommandPrefix(resolver),
              assemblerFlags,
              filteredAsmSrcs,
              ImmutableList.<Path>builder()
                  .addAll(assemblerIncludeDirs)
                  .add(asmHeaderPath.get().getParent())
                  .add(asmIncludeDir)
                  .build(),
              platform,
              asmOutputPath));
      asmOutputs.add(asmOutputPath);
    }

    if (!getAsmSources(groupedSrcs).isEmpty() || !extraAsmOutputs.isEmpty()) {
      steps.add(
          new GoPackStep(
              getProjectFilesystem().getRootPath(),
              packer.getEnvironment(resolver),
              packer.getCommandPrefix(resolver),
              GoPackStep.Operation.APPEND,
              asmOutputs
                  .addAll(extraAsmOutputs.stream().map(x -> resolver.getAbsolutePath(x)).iterator())
                  .build(),
              filteredAsmSrcs,
              output));
    }

    return steps.build();
  }

  static List<Path> getSourceFiles(ImmutableSet<SourcePath> srcPaths, BuildContext context) {
    List<Path> srcFiles = new ArrayList<>();
    for (SourcePath path : srcPaths) {
      Path srcPath = context.getSourcePathResolver().getAbsolutePath(path);
      if (Files.isDirectory(srcPath)) {
        try (Stream<Path> sourcePaths = Files.list(srcPath)) {
          srcFiles.addAll(sourcePaths.filter(Files::isRegularFile).collect(Collectors.toList()));
        } catch (IOException e) {
          throw new RuntimeException("An error occur when listing the files under " + srcPath, e);
        }
      } else {
        srcFiles.add(srcPath);
      }
    }
    return srcFiles;
  }

  @Nullable
  private ImmutableList<Path> getAsmSources(ImmutableMap<String, ImmutableList<Path>> groupedSrcs) {
    return groupedSrcs.get("s");
  }

  @Nullable
  private ImmutableList<Path> getGoSources(ImmutableMap<String, ImmutableList<Path>> groupedSrcs) {
    return groupedSrcs.get("go");
  }

  @Nullable
  private ImmutableList<Path> getHeaderSources(
      ImmutableMap<String, ImmutableList<Path>> groupedSrcs) {
    return groupedSrcs.get("h");
  }

  private ImmutableMap<String, ImmutableList<Path>> getGroupedSrcs(List<Path> srcFiles) {
    ImmutableList.Builder<Path> compileSrcListBuilder = ImmutableList.builder();
    ImmutableList.Builder<Path> headerSrcListBuilder = ImmutableList.builder();
    ImmutableList.Builder<Path> asmSrcListBuilder = ImmutableList.builder();

    for (Path sourceFile : srcFiles) {
      String extension = MorePaths.getFileExtension(sourceFile).toLowerCase();
      if (extension.equals("s")) {
        asmSrcListBuilder.add(sourceFile);
      } else if (extension.equals("go")) {
        compileSrcListBuilder.add(sourceFile);
      } else {
        headerSrcListBuilder.add(sourceFile);
      }
    }

    return ImmutableMap.of(
        "s", asmSrcListBuilder.build(),
        "go", compileSrcListBuilder.build(),
        "h", headerSrcListBuilder.build());
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }
}

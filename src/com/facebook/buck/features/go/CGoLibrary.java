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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxLinkAndCompileRules;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSymlinkTreeHeaders;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Streams;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The CGoLibrary represents cgo build process which outputs the linkable object that is appended to
 * the native go compiled program (via pack tool).
 *
 * <p>The process consists of four steps (similiar to go build): 1. Generate c sources with cgo tool
 * 2. Compile and link cgo sources into single object 3. Generate cgo_import.go 4. Return generated
 * go files and linked object (used by GoCompile)
 */
public class CGoLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps {
  private final ImmutableList<SourcePath> goFiles;
  private final SourcePath output;
  private final Iterable<BuildRule> linkableDeps;

  private CGoLibrary(
      BuildRuleParams params,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableList<SourcePath> goFiles,
      SourcePath output,
      Iterable<BuildRule> linkableDeps) {
    super(buildTarget, projectFilesystem, params);

    this.goFiles = goFiles;
    this.output = output;
    this.linkableDeps = linkableDeps;
  }

  public static BuildRule create(
      BuildRuleParams params,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver pathResolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      GoPlatform platform,
      CgoLibraryDescriptionArg args,
      Iterable<BuildTarget> cxxDeps,
      Tool cgo) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    CxxDeps allDeps =
        CxxDeps.builder().addDeps(cxxDeps).addPlatformDeps(args.getPlatformDeps()).build();

    // generate C sources with cgo tool (go build writes c files to _obj dir)
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            buildTarget,
            graphBuilder,
            ruleFinder,
            pathResolver,
            Optional.of(platform.getCxxPlatform()),
            args);

    HeaderSymlinkTree headerSymlinkTree =
        getHeaderSymlinkTree(
            buildTarget,
            projectFilesystem,
            ruleFinder,
            graphBuilder,
            platform.getCxxPlatform(),
            cxxDeps,
            headers);

    ImmutableSet.Builder<SourcePath> cxxSourcesFromArg = ImmutableSet.builder();
    ImmutableSet.Builder<SourcePath> goSourcesFromArg = ImmutableSet.builder();

    for (SourceWithFlags srcWithFlags : args.getSrcs()) {
      SourcePath pth = srcWithFlags.getSourcePath();
      String ext = Files.getFileExtension(pathResolver.getAbsolutePath(pth).toString());

      if (CxxSource.Type.fromExtension(ext).equals(Optional.of(CxxSource.Type.C))) {
        cxxSourcesFromArg.add(pth);
      } else if (ext.equals("go")) {
        goSourcesFromArg.add(pth);
      }
    }

    CGoGenSource genSource =
        (CGoGenSource)
            graphBuilder.computeIfAbsent(
                buildTarget.withAppendedFlavors(InternalFlavor.of("cgo-gen-sources")),
                target ->
                    new CGoGenSource(
                        target,
                        projectFilesystem,
                        ruleFinder,
                        pathResolver,
                        goSourcesFromArg.build(),
                        headerSymlinkTree,
                        cgo,
                        args.getCgoCompilerFlags(),
                        platform));

    // generated c files needs to be compiled and linked into a single object
    // file (equivalent of (_cgo_.o), includes:
    //   * _cgo_export.o
    //   * _cgo_main.o
    //   * all of the *.cgo2.o
    CxxLink cgoBin =
        (CxxLink)
            graphBuilder.computeIfAbsent(
                buildTarget.withAppendedFlavors(InternalFlavor.of("cgo-first-step")),
                target ->
                    nativeBinCompilation(
                        target,
                        projectFilesystem,
                        graphBuilder,
                        pathResolver,
                        cellRoots,
                        cxxBuckConfig,
                        platform.getCxxPlatform(),
                        args,
                        new ImmutableList.Builder<BuildRule>()
                            .add(genSource)
                            .addAll(allDeps.get(graphBuilder, platform.getCxxPlatform()))
                            .build(),
                        new ImmutableMap.Builder<Path, SourcePath>()
                            .putAll(headers)
                            .put(
                                pathResolver
                                    .getAbsolutePath(genSource.getExportHeader())
                                    .getFileName(),
                                genSource.getExportHeader())
                            .build(),
                        new ImmutableList.Builder<SourcePath>()
                            .addAll(cxxSourcesFromArg.build())
                            .addAll(genSource.getCFiles())
                            .addAll(genSource.getCgoFiles())
                            .build(),
                        args.getLinkerFlags()));

    // generate cgo_import.h with previously generated object file (_cgo.o)
    BuildRule cgoImport =
        graphBuilder.computeIfAbsent(
            buildTarget.withAppendedFlavors(InternalFlavor.of("cgo-gen-import")),
            target ->
                new CGoGenImport(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    pathResolver,
                    cgo,
                    platform,
                    // take first source file in the list to infer the package
                    // name via go list
                    goSourcesFromArg.build().iterator().next(),
                    Objects.requireNonNull(cgoBin.getSourcePathToOutput())));

    // filter out compiled object only for the sources we are interested in
    ImmutableList<String> linkableObjectFiles =
        Stream.concat(
                cxxSourcesFromArg
                    .build()
                    .stream()
                    .map(x -> pathResolver.getAbsolutePath(x).getFileName().toString()),
                ImmutableList.of(".cgo2.c", "_cgo_export.c").stream())
            .collect(ImmutableList.toImmutableList());

    // generate final object file (equivalent of _all.o) which includes:
    //  * _cgo_export.o
    //  * all of the *.cgo2.o files
    ImmutableList<Arg> cxxArgs =
        ImmutableList.<Arg>builder()
            .addAll(StringArg.from("-r", "-nostdlib"))
            .addAll(
                cgoBin
                    .getArgs()
                    .stream()
                    .filter(FileListableLinkerInputArg.class::isInstance)
                    .map(FileListableLinkerInputArg.class::cast)
                    .filter(
                        arg -> {
                          Path pth = pathResolver.getAbsolutePath(arg.getPath());
                          String fileName = pth.getFileName().toString();
                          return pth.toString().contains("cgo-first-step")
                              && linkableObjectFiles.stream().anyMatch(x -> fileName.contains(x));
                        })
                    .collect(ImmutableList.toImmutableList()))
            .build();

    CxxLink cgoAllBin =
        (CxxLink)
            graphBuilder.computeIfAbsent(
                buildTarget.withAppendedFlavors(InternalFlavor.of("cgo-second-step")),
                target ->
                    CxxLinkableEnhancer.createCxxLinkableBuildRule(
                        cellRoots,
                        cxxBuckConfig,
                        platform.getCxxPlatform(),
                        projectFilesystem,
                        graphBuilder,
                        ruleFinder,
                        target,
                        BuildTargetPaths.getGenPath(projectFilesystem, target, "%s/_all"),
                        ImmutableMap.of(),
                        cxxArgs, // collection of selected object files
                        args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC_PIC),
                        CxxLinkOptions.of(),
                        Optional.empty()));

    // output (referenced later on by GoCompile) provides:
    // * _cgo_gotypes.go
    // * _cgo_import.go
    // * all of the *.cgo1.go files
    //
    // the go sources should be appended to sources list and _all.o file should
    // be appended to the output binary (pack step)
    return graphBuilder.computeIfAbsent(
        buildTarget,
        target ->
            new CGoLibrary(
                params
                    .withDeclaredDeps(
                        ImmutableSortedSet.<BuildRule>naturalOrder()
                            .addAll(
                                ruleFinder.filterBuildRuleInputs(cgoAllBin.getSourcePathToOutput()))
                            .addAll(
                                ruleFinder.filterBuildRuleInputs(
                                    new Builder<SourcePath>()
                                        .addAll(genSource.getGoFiles())
                                        .add(
                                            Objects.requireNonNull(
                                                cgoImport.getSourcePathToOutput()))
                                        .build()))
                            .build())
                    .withoutExtraDeps(),
                target,
                projectFilesystem,
                new Builder<SourcePath>()
                    .addAll(genSource.getGoFiles())
                    .add(Objects.requireNonNull(cgoImport.getSourcePathToOutput()))
                    .build(),
                Objects.requireNonNull(cgoAllBin.getSourcePathToOutput()),
                Objects.requireNonNull(allDeps.get(graphBuilder, platform.getCxxPlatform()))));
  }

  private static HeaderSymlinkTree getHeaderSymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      Iterable<BuildTarget> cxxDeps,
      ImmutableMap<Path, SourcePath> headers) {

    ImmutableList<BuildRule> cxxDepsRules =
        Streams.stream(cxxDeps)
            .map(graphBuilder::requireRule)
            .collect(ImmutableList.toImmutableList());

    Collection<CxxPreprocessorInput> cxxPreprocessorInputs =
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            cxxPlatform, graphBuilder, cxxDepsRules);

    ImmutableMap.Builder<Path, SourcePath> allHeaders = ImmutableMap.builder();

    // scan CxxDeps for headers and add them to allHeaders
    HashMap<Path, SourcePath> cxxDepsHeaders = new HashMap<Path, SourcePath>();
    cxxPreprocessorInputs
        .stream()
        .flatMap(input -> input.getIncludes().stream())
        .filter(header -> header instanceof CxxSymlinkTreeHeaders)
        .flatMap(header -> ((CxxSymlinkTreeHeaders) header).getNameToPathMap().entrySet().stream())
        .forEach(entry -> cxxDepsHeaders.put(entry.getKey(), entry.getValue()));
    allHeaders.putAll(cxxDepsHeaders);

    // add headers defined within the cgo_library rule
    allHeaders.putAll(headers);

    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        graphBuilder,
        cxxPlatform,
        allHeaders.build(),
        HeaderVisibility.PUBLIC,
        true);
  }

  private static CxxLink nativeBinCompilation(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver pathResolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CgoLibraryDescriptionArg args,
      Iterable<BuildRule> deps,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableList<SourcePath> sources,
      ImmutableList<StringWithMacros> flags) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    ImmutableMap<String, CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(
            buildTarget,
            graphBuilder,
            ruleFinder,
            pathResolver,
            cxxPlatform,
            wrapSourcePathsWithFlags(sources),
            PatternMatchedCollection.of());

    CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinary(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            srcs,
            headers,
            ImmutableSortedSet.<BuildRule>naturalOrder().addAll(deps).build(),
            ImmutableSet.of(),
            Optional.empty(),
            Optional.empty(),
            args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC_PIC),
            CxxLinkOptions.of(),
            args.getPreprocessorFlags(),
            args.getPlatformPreprocessorFlags(),
            args.getLangPreprocessorFlags(),
            args.getLangPlatformPreprocessorFlags(),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(),
            args.getCompilerFlags(),
            args.getLangCompilerFlags(),
            args.getPlatformCompilerFlags(),
            args.getLangPlatformCompilerFlags(),
            Optional.empty(),
            Optional.empty(),
            flags,
            args.getLinkerExtraOutputs(),
            args.getPlatformLinkerFlags(),
            Optional.empty(),
            args.getRawHeaders(),
            Optional.empty());

    return cxxLinkAndCompileRules.getCxxLink();
  }

  private static ImmutableSortedSet<SourceWithFlags> wrapSourcePathsWithFlags(
      Iterable<SourcePath> it) {
    ImmutableSortedSet.Builder<SourceWithFlags> builder = ImmutableSortedSet.naturalOrder();
    for (SourcePath sourcePath : it) {
      builder.add(SourceWithFlags.of(sourcePath));
    }
    return builder.build();
  }

  /** returns .go files produced by cgo tool */
  public ImmutableList<SourcePath> getGeneratedGoSource() {
    return goFiles;
  }

  /** returns compiled linkable file source path */
  public SourcePath getOutput() {
    return output;
  }

  public Iterable<BuildRule> getLinkableDeps() {
    return linkableDeps;
  }
}

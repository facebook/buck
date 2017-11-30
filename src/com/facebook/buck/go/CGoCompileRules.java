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

package com.facebook.buck.go;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLinkAndCompileRules;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;

/**
 * The CGoCompileRules represents cgo build process which outputs the linkable object that is
 * appended to the native go compiled program (via pack tool).
 *
 * <p>The process consists of four steps (similiar to go build): 1. Generate c sources with cgo tool
 * 2. Compile and link cgo sources into single object 3. Generate cgo_import.go 4. Return generated
 * go files and linked object (used by GoCompile)
 */
public class CGoCompileRules implements AddsToRuleKey {
  @AddToRuleKey private final ImmutableList<SourcePath> goFiles;
  @AddToRuleKey private final SourcePath output;
  private final ImmutableSortedSet<BuildRule> deps;

  public CGoCompileRules(
      ImmutableList<SourcePath> goFiles, SourcePath output, ImmutableSortedSet<BuildRule> deps) {
    this.goFiles = goFiles;
    this.output = output;
    this.deps = deps;
  }

  public SortedSet<BuildRule> getDeps() {
    return deps;
  }

  public ImmutableList<SourcePath> getGeneratedGoSource() {
    return goFiles;
  }

  public SourcePath getOutputBinary() {
    return output;
  }

  public static CGoCompileRules create(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      GoPlatform platform,
      ImmutableSet<SourcePath> cgoSrcs,
      ImmutableSet<SourcePath> cgoHeaders,
      Iterable<BuildTarget> cgoDeps,
      Tool cgo,
      Path packageName) {

    // generate C sources with cgo tool (go build writes c files to _obj dir)
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    CGoGenSource genSource =
        (CGoGenSource)
            ruleResolver.computeIfAbsent(
                buildTarget,
                target ->
                    new CGoGenSource(
                        buildTarget,
                        projectFilesystem,
                        ruleFinder,
                        pathResolver,
                        cgoSrcs,
                        cgo,
                        platform));

    // generated c files needs to be compiled and linked into a single object
    // file (equivalent of (_cgo_.o), includes:
    //   * _cgo_export.o
    //   * _cgo_main.o
    //   * all of the *.cgo2.o
    BuildRule cgoBin =
        ruleResolver.computeIfAbsent(
            buildTarget.withAppendedFlavors(InternalFlavor.of("cgo-first-step")),
            target ->
                nativeBinCompilation(
                    target,
                    projectFilesystem,
                    ruleResolver,
                    pathResolver,
                    cellRoots,
                    cxxBuckConfig,
                    cxxPlatform,
                    ImmutableSortedSet.of(genSource),
                    ImmutableSortedSet.<SourcePath>naturalOrder()
                        .add(genSource.getExportHeader())
                        .addAll(cgoHeaders)
                        .build(),
                    new ImmutableList.Builder<SourcePath>()
                        .addAll(genSource.getCFiles())
                        .addAll(genSource.getCgoFiles())
                        .build(),
                    cgoDeps,
                    ImmutableList.of()));

    // generate cgo_import.h with previously generated object file (_cgo.o)
    BuildRule cgoImport =
        ruleResolver.computeIfAbsent(
            buildTarget.withAppendedFlavors(InternalFlavor.of("cgo-gen-cgo_import.go")),
            target ->
                new CGoGenImport(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    pathResolver,
                    cgo,
                    platform,
                    packageName,
                    Preconditions.checkNotNull(cgoBin.getSourcePathToOutput())));

    // TODO: performance improvement: those object were compiled in step 1 (used
    // to generate _cgo_import.go). The objects should be linked toghether not
    // compiled again.
    //
    // generate final object file (equivalent of _all.o) which includes:
    //  * _cgo_export.o
    //  * all of the *.cgo2.o files
    BuildRule cgoAllBin =
        ruleResolver.computeIfAbsent(
            buildTarget.withAppendedFlavors(InternalFlavor.of("cgo-second-step")),
            target ->
                nativeBinCompilation(
                    target,
                    projectFilesystem,
                    ruleResolver,
                    pathResolver,
                    cellRoots,
                    cxxBuckConfig,
                    cxxPlatform,
                    ImmutableSortedSet.of(cgoImport),
                    ImmutableSortedSet.<SourcePath>naturalOrder()
                        .add(genSource.getExportHeader())
                        .addAll(cgoHeaders)
                        .build(),
                    genSource.getCFiles(),
                    cgoDeps,
                    wrapFlags(ImmutableList.of("-r", "-nostdlib"))));

    Preconditions.checkNotNull(cgoAllBin.getSourcePathToOutput());

    // output (referenced later on by GoCompile) provides:
    // * _cgo_gotypes.go
    // * _cgo_import.go
    // * all of the *.cgo1.go files
    //
    // the go sources should be appended to sources list and _all.o file should
    // be appended to the output binary (pack step)
    return new CGoCompileRules(
        new ImmutableList.Builder<SourcePath>()
            .addAll(genSource.getGoFiles())
            .add(Preconditions.checkNotNull(cgoImport.getSourcePathToOutput()))
            .build(),
        cgoAllBin.getSourcePathToOutput(),
        ImmutableSortedSet.of(cgoImport, cgoAllBin));
  }

  private static BuildRule nativeBinCompilation(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSortedSet<SourcePath> rawHeaders,
      ImmutableList<SourcePath> sources,
      Iterable<BuildTarget> cgoDeps,
      ImmutableList<StringWithMacros> flags) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    ImmutableMap<String, CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(
            buildTarget,
            ruleResolver,
            ruleFinder,
            pathResolver,
            cxxPlatform,
            wrapSourcePathsWithFlags(sources),
            PatternMatchedCollection.of());

    ImmutableSet.Builder<BuildRule> cgoRules = ImmutableSet.builder();
    for (BuildTarget target : cgoDeps) {
      cgoRules.add(ruleResolver.requireRule(target));
    }

    ImmutableMap.Builder<Path, SourcePath> headers = ImmutableMap.builder();
    for (SourcePath header : rawHeaders) {
      headers.put(projectFilesystem.relativize(pathResolver.getAbsolutePath(header)), header);
    }

    CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinary(
            buildTarget,
            projectFilesystem,
            ruleResolver,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            srcs,
            headers.build(),
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(deps)
                .addAll(cgoRules.build())
                .build(),
            ImmutableSet.of(),
            Optional.empty(),
            Optional.empty(),
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            ImmutableList.of(),
            PatternMatchedCollection.of(),
            ImmutableMap.of(),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(),
            // used for compilers other than gcc (due to __gcc_struct__)
            wrapFlags(ImmutableList.of("-Wno-unknown-attributes")),
            ImmutableMap.of(),
            PatternMatchedCollection.of(),
            Optional.empty(),
            Optional.empty(),
            flags,
            ImmutableList.of(),
            PatternMatchedCollection.of(),
            Optional.empty(),
            ImmutableList.of(),
            rawHeaders);

    return cxxLinkAndCompileRules.getBinaryRule();
  }

  private static ImmutableList<StringWithMacros> wrapFlags(ImmutableList<String> flags) {
    ImmutableList.Builder<StringWithMacros> builder = ImmutableList.builder();
    for (String flag : flags) {
      builder.add(StringWithMacros.of(ImmutableList.of(Either.ofLeft(flag))));
    }
    return builder.build();
  }

  private static ImmutableSortedSet<SourceWithFlags> wrapSourcePathsWithFlags(
      Iterable<SourcePath> it) {
    ImmutableSortedSet.Builder<SourceWithFlags> builder = ImmutableSortedSet.naturalOrder();
    for (SourcePath sourcePath : it) {
      builder.add(SourceWithFlags.of(sourcePath));
    }
    return builder.build();
  }
}

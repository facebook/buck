/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx.toolchain.linker.impl;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.linker.HasIncrementalThinLTO;
import com.facebook.buck.cxx.toolchain.linker.HasLTO;
import com.facebook.buck.cxx.toolchain.linker.HasLinkerMap;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.objectfile.LcUuidContentsScrubber;
import com.facebook.buck.cxx.toolchain.objectfile.OsoSymbolsContentsScrubber;
import com.facebook.buck.cxx.toolchain.objectfile.StripDebugSymbolTableScrubber;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A specialization of {@link Linker} containing information specific to the Darwin implementation.
 */
public class DarwinLinker extends DelegatingTool
    implements Linker, HasLinkerMap, HasIncrementalThinLTO, HasLTO {

  private final boolean cacheLinks;
  private final boolean scrubConcurrently;
  private final boolean hasFocusedTargets;

  @AddToRuleKey private final boolean usePathNormalizationArgs;

  public DarwinLinker(
      Tool tool,
      boolean cacheLinks,
      boolean scrubConcurrently,
      boolean usePathNormalizationArgs,
      boolean hasFocusedTargets) {
    super(tool);
    this.cacheLinks = cacheLinks;
    this.scrubConcurrently = scrubConcurrently;
    this.usePathNormalizationArgs = usePathNormalizationArgs;
    this.hasFocusedTargets = hasFocusedTargets;
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(
      ImmutableMap<Path, Path> cellRootMap, ImmutableSet<AbsPath> focusedTargetsPaths) {
    if (cacheLinks) {
      FileScrubber uuidScrubber = new LcUuidContentsScrubber(scrubConcurrently);
      if (usePathNormalizationArgs) {
        // Path normalization would happen via pathNormalizationArgs()
        return ImmutableList.of(uuidScrubber);
      }
      return ImmutableList.of(new OsoSymbolsContentsScrubber(cellRootMap), uuidScrubber);
    } else if (hasFocusedTargets) {
      return getFocusedDebugSymbolScrubbers(focusedTargetsPaths);
    } else {
      return ImmutableList.of();
    }
  }

  /**
   * Acquires the scrubber to enable focused debug symbols - loading debug symbols only for focused
   * targets. To do that, we'll scrub all unfocused targets to have fake paths to their .o files in
   * the linked binaries' symbol tables. We'll preserve the correct paths to .o files for only the
   * focused targets.
   *
   * <p>For example:
   *
   * <p>For a binary that original includes: /Users/tmp/buck-out/some/object/file1.o
   * /Users/tmp/buck-out/some/object/file2.o /Users/tmp/buck-out/some/object/libTest.a(file3.o)
   * /Users/tmp/buck-out/some/object/libTest.a(file4.o)
   * /Users/tmp/buck-out/some/object/libHouse.a(file5.o)
   *
   * <p>And when given these focused targets absolute paths: /Users/tmp/buck-out/some/object/file1.o
   * /Users/tmp/buck-out/some/object/libTest.a
   *
   * <p>Then scrub the linked binary with OsoSymbolsContentsScrubber. Eventually the linked binary
   * will have: /Users/tmp/buck-out/some/object/file1.o -> SAME
   * /Users/tmp/buck-out/some/object/file2.o -> fake/path/file.o
   * /Users/tmp/buck-out/some/object/libTest.a(file3.o) -> SAME
   * /Users/tmp/buck-out/some/object/libTest.a(file4.o) -> SAME
   * /Users/tmp/buck-out/some/object/libHouse.a(file5.o) -> fake/path/file.o
   *
   * <p>For linked binaries with no focused targets, we call "strip -S" on them to strip their debug
   * symbol tables.
   *
   * @param focusedTargetsAbsolutePaths the relative paths to the focused targets' build outputs.
   * @return the file scrubber that'll scrub the binary to only contain debug symbols for focused
   *     targets.
   */
  private ImmutableList<FileScrubber> getFocusedDebugSymbolScrubbers(
      ImmutableSet<AbsPath> focusedTargetsAbsolutePaths) {
    if (!focusedTargetsAbsolutePaths.isEmpty()) {
      ImmutableSet<Path> focusedTargetsPaths =
          focusedTargetsAbsolutePaths.stream()
              .map(AbsPath::getPath)
              .collect(ImmutableSet.toImmutableSet());

      return ImmutableList.of(new OsoSymbolsContentsScrubber(focusedTargetsPaths));
    } else {
      return ImmutableList.of(new StripDebugSymbolTableScrubber());
    }
  }

  @Override
  public Iterable<Arg> pathNormalizationArgs(ImmutableMap<Path, Path> cellRootMap) {
    if (cacheLinks && usePathNormalizationArgs) {
      Optional<String> maybeOsoPrefix =
          OsoSymbolsContentsScrubber.computeOsoPrefixForCellRootMap(cellRootMap);
      return maybeOsoPrefix
          .map(
              osoPrefix ->
                  ImmutableList.<Arg>of(
                      StringArg.of("-Xlinker"), StringArg.of("-oso_prefix"),
                      StringArg.of("-Xlinker"), StringArg.of(osoPrefix)))
          .orElse(ImmutableList.of());
    }

    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> linkWhole(Arg input, SourcePathResolverAdapter resolver) {
    return ImmutableList.of(
        StringArg.of("-Xlinker"), StringArg.of("-force_load"), StringArg.of("-Xlinker"), input);
  }

  @Override
  public Iterable<Arg> linkerMap(Path output) {
    // Build up the arguments to pass to the linker.
    return StringArg.from("-Xlinker", "-map", "-Xlinker", linkerMapPath(output).toString());
  }

  @Override
  public Path linkerMapPath(Path output) {
    return Paths.get(output + "-LinkMap.txt");
  }

  @Override
  public Iterable<Arg> thinLTO(Path output) {
    return StringArg.from(
        "-flto=thin", "-Xlinker", "-object_path_lto", "-Xlinker", ltoPath(output).toString());
  }

  @Override
  public Iterable<Arg> incrementalThinLTOFlags(Path output) {
    return StringArg.from(
        "-Wl,-thinlto_emit_indexes",
        "-Wl,-thinlto_emit_imports",
        "-Xlinker",
        "-thinlto_new_prefix",
        "-Xlinker",
        output.toString());
  }

  @Override
  public Iterable<Arg> fatLTO(Path output) {
    // For fat LTO, the object path should be a file.
    return StringArg.from(
        "-flto", "-Xlinker", "-object_path_lto", "-Xlinker", ltoPath(output).toString());
  }

  @Override
  public Path ltoPath(Path output) {
    return Paths.get(output + "-lto");
  }

  @Override
  public Iterable<String> soname(String arg) {
    return Linkers.iXlinker("-install_name", "@rpath/" + arg);
  }

  @Override
  public Iterable<Arg> fileList(Path fileListPath) {
    return ImmutableList.of(
        StringArg.of("-Xlinker"),
        StringArg.of("-filelist"),
        StringArg.of("-Xlinker"),
        StringArg.of(fileListPath.toString()));
  }

  @Override
  public String origin() {
    return "@executable_path";
  }

  @Override
  public String libOrigin() {
    return "@loader_path";
  }

  @Override
  public String searchPathEnvVar() {
    return "DYLD_LIBRARY_PATH";
  }

  @Override
  public String preloadEnvVar() {
    return "DYLD_INSERT_LIBRARIES";
  }

  @Override
  public ImmutableList<Arg> createUndefinedSymbolsLinkerArgs(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      BuildTarget target,
      ImmutableList<? extends SourcePath> symbolFiles) {
    return ImmutableList.of(new UndefinedSymbolsArg(symbolFiles));
  }

  @Override
  public ImmutableList<Arg> createGlobalSymbolsLinkerArgs(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      BuildTarget target,
      ImmutableList<? extends SourcePath> symbolFiles,
      ImmutableList<String> extraGlobals) {
    return ImmutableList.of(new ExportedSymbolsArg(symbolFiles, extraGlobals));
  }

  @Override
  public String getExportDynamicSymbolFlag() {
    return "-exported_symbol";
  }

  @Override
  public Iterable<String> getNoAsNeededSharedLibsFlags() {
    return ImmutableList.of();
  }

  @Override
  public Iterable<String> getIgnoreUndefinedSymbolsFlags() {
    return Linkers.iXlinker("-flat_namespace", "-undefined", "suppress");
  }

  @Override
  public Iterable<Arg> getSharedLibFlag() {
    return ImmutableList.of(StringArg.of("-shared"));
  }

  @Override
  public Iterable<String> outputArgs(String path) {
    return ImmutableList.of("-o", path);
  }

  @Override
  public SharedLibraryLoadingType getSharedLibraryLoadingType() {
    return SharedLibraryLoadingType.RPATH;
  }

  @Override
  public Optional<ExtraOutputsDeriver> getExtraOutputsDeriver() {
    return Optional.empty();
  }

  @Override
  public boolean getUseUnixPathSeparator() {
    return true;
  }

  /**
   * An {@link Arg} which reads symbols from files and propagates them to the Darwin linker via the
   * argument returned by {@link SymbolsArg#getLinkerArgument()}.
   *
   * <p>NOTE: this is prone to overrunning command line argument limits.
   */
  private abstract static class SymbolsArg implements Arg {
    @AddToRuleKey private final ImmutableList<? extends SourcePath> symbolFiles;
    @AddToRuleKey private final ImmutableList<String> extraSymbols;

    public SymbolsArg(
        ImmutableList<? extends SourcePath> symbolFiles, ImmutableList<String> extraSymbols) {
      this.symbolFiles = symbolFiles;
      this.extraSymbols = extraSymbols;
    }

    abstract String getLinkerArgument();

    String getSymbolPrefix() {
      return "";
    }

    // Open all the symbol files and read in all undefined symbols, passing them to linker using the
    // `-u` command line option.
    @Override
    public void appendToCommandLine(
        Consumer<String> consumer, SourcePathResolverAdapter pathResolver) {
      Set<String> symbols = new LinkedHashSet<>();
      try {
        for (SourcePath path : symbolFiles) {
          symbols.addAll(
              Files.readAllLines(
                  pathResolver.getAbsolutePath(path).getPath(), StandardCharsets.UTF_8));
        }
        symbols.addAll(extraSymbols);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      for (String symbol : symbols) {
        Linkers.iXlinker(getLinkerArgument(), getSymbolPrefix() + symbol).forEach(consumer);
      }
    }

    @Override
    public String toString() {
      return "symbols(" + Joiner.on(',').join(symbolFiles) + ")";
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof SymbolsArg)) {
        return false;
      }
      SymbolsArg symbolsArg = (SymbolsArg) other;
      return Objects.equals(symbolFiles, symbolsArg.symbolFiles);
    }

    @Override
    public int hashCode() {
      return Objects.hash(symbolFiles);
    }
  }

  /**
   * An {@link Arg} which reads undefined symbols from files and propagates them to the Darwin
   * linker via the `-u` argument.
   *
   * <p>NOTE: this is prone to overrunning command line argument limits, but it's not clear of
   * another way to do this (perhaps other than creating a dummy object file whose symbol table only
   * contains the undefined symbols listed in the symbol files).
   */
  private static class UndefinedSymbolsArg extends SymbolsArg {

    public UndefinedSymbolsArg(ImmutableList<? extends SourcePath> symbolFiles) {
      super(symbolFiles, ImmutableList.of());
    }

    @Override
    String getLinkerArgument() {
      return "-u";
    }
  }

  /**
   * An {@link Arg} which reads global symbols from files and propagates them to the Darwin linker
   * via the `-exported_symbol` argument.
   *
   * <p>NOTE: this is prone to overrunning command line argument limits, and
   * `-exported_symbols_list` argument should be leveraged to pass all symbols at once.
   */
  private static class ExportedSymbolsArg extends SymbolsArg {

    public ExportedSymbolsArg(
        ImmutableList<? extends SourcePath> symbolFiles, ImmutableList<String> extraGlobals) {
      super(symbolFiles, extraGlobals);
    }

    @Override
    String getLinkerArgument() {
      return "-exported_symbol";
    }

    /**
     * *
     *
     * <p>NOTE: `-exported_symbol` fails with an undefined symbol error when it is passed a symbol
     * that does not exist. Using a wildcard prefix * fixes this. But, it may over-export some
     * symbols, especially with C linkage.
     *
     * @return A wildcard prefix for each symbol
     */
    @Override
    String getSymbolPrefix() {
      return "*";
    }
  }
}

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

package com.facebook.buck.cxx.toolchain.linker;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.objectfile.LcUuidContentsScrubber;
import com.facebook.buck.cxx.toolchain.objectfile.OsoSymbolsContentsScrubber;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A specialization of {@link Linker} containing information specific to the Darwin implementation.
 */
public class DarwinLinker extends DelegatingTool implements Linker, HasLinkerMap, HasThinLTO {
  public DarwinLinker(Tool tool) {
    super(tool);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(ImmutableMap<Path, Path> cellRootMap) {
    return ImmutableList.of(
        new OsoSymbolsContentsScrubber(cellRootMap), new LcUuidContentsScrubber());
  }

  @Override
  public Iterable<Arg> linkWhole(Arg input) {
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
        "-flto=thin", "-Xlinker", "-object_path_lto", "-Xlinker", thinLTOPath(output).toString());
  }

  @Override
  public Path thinLTOPath(Path output) {
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
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      ImmutableList<? extends SourcePath> symbolFiles) {
    return ImmutableList.of(new UndefinedSymbolsArg(symbolFiles));
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
  public boolean hasFilePathSizeLimitations() {
    return false;
  }

  @Override
  public SharedLibraryLoadingType getSharedLibraryLoadingType() {
    return SharedLibraryLoadingType.RPATH;
  }

  /**
   * An {@link Arg} which reads undefined symbols from files and propagates them to the Darwin
   * linker via the `-u` argument.
   *
   * <p>NOTE: this is prone to overrunning command line argument limits, but it's not clear of
   * another way to do this (perhaps other than creating a dummy object file whose symbol table only
   * contains the undefined symbols listed in the symbol files).
   */
  private static class UndefinedSymbolsArg implements Arg {
    @AddToRuleKey private final ImmutableList<? extends SourcePath> symbolFiles;

    public UndefinedSymbolsArg(ImmutableList<? extends SourcePath> symbolFiles) {
      this.symbolFiles = symbolFiles;
    }

    // Open all the symbol files and read in all undefined symbols, passing them to linker using the
    // `-u` command line option.
    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
      Set<String> symbols = new LinkedHashSet<>();
      try {
        for (SourcePath path : symbolFiles) {
          symbols.addAll(Files.readAllLines(pathResolver.getAbsolutePath(path), Charsets.UTF_8));
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      for (String symbol : symbols) {
        Linkers.iXlinker("-u", symbol).forEach(consumer);
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
      if (!(other instanceof UndefinedSymbolsArg)) {
        return false;
      }
      UndefinedSymbolsArg symbolsArg = (UndefinedSymbolsArg) other;
      return Objects.equals(symbolFiles, symbolsArg.symbolFiles);
    }

    @Override
    public int hashCode() {
      return Objects.hash(symbolFiles);
    }
  }
}

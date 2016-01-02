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

package com.facebook.buck.cxx;

import com.facebook.buck.io.FileScrubber;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A specialization of {@link Linker} containing information specific to the Darwin implementation.
 */
public class DarwinLinker implements Linker {

  private final Tool tool;

  public DarwinLinker(Tool tool) {
    this.tool = tool;
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    return tool.getDeps(resolver);
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return tool.getInputs();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return tool.getCommandPrefix(resolver);
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return tool.getEnvironment(resolver);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(ImmutableCollection<Path> cellRoots) {
    return ImmutableList.of(
        new OsoSymbolsScrubber(cellRoots),
        new LcUuidScrubber());
  }

  @Override
  public Iterable<Arg> linkWhole(Arg input) {
    return ImmutableList.of(
        new StringArg("-Xlinker"),
        new StringArg("-force_load"),
        new StringArg("-Xlinker"),
        input);
  }

  @Override
  public Iterable<String> soname(String arg) {
    return Linkers.iXlinker("-install_name", "@rpath/" + arg);
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
  public ImmutableList<Arg> createUndefinedSymbolsLinkerArgs(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      BuildTarget target,
      Iterable<? extends SourcePath> symbolFiles) {
    return ImmutableList.<Arg>of(new UndefinedSymbolsArg(pathResolver, symbolFiles));
  }

  @Override
  public Iterable<String> getNoAsNeededSharedLibsFlags() {
    return ImmutableList.of();
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder
        .setReflectively("tool", tool)
        .setReflectively("type", getClass().getSimpleName());
  }

  /**
   * An {@link Arg} which reads undefined symbols from files and propagates them to the Darwin
   * linker via the `-u` argument.
   *
   * NOTE: this is prone to overrunning command line argument limits, but it's not clear of another
   * way to do this (perhaps other than creating a dymmy object file whose symbol table only
   * contains the undefined symbols listed in the symbol files).
   */
  private static class UndefinedSymbolsArg extends Arg {

    private final SourcePathResolver pathResolver;
    private final Iterable<? extends SourcePath> symbolFiles;

    public UndefinedSymbolsArg(
        SourcePathResolver pathResolver,
        Iterable<? extends SourcePath> symbolFiles) {
      this.pathResolver = pathResolver;
      this.symbolFiles = symbolFiles;
    }

    @Override
    public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
      return pathResolver.filterBuildRuleInputs(symbolFiles);
    }

    // Open all the symbol files and read in all undefined symbols, passing them to linker using the
    // `-u` command line option.
    @Override
    public void appendToCommandLine(ImmutableCollection.Builder<String> builder) {
      Set<String> symbols = new LinkedHashSet<>();
      try {
        for (SourcePath path : symbolFiles) {
          symbols.addAll(Files.readAllLines(pathResolver.getAbsolutePath(path), Charsets.UTF_8));
        }
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
      for (String symbol : symbols) {
        builder.addAll(Linkers.iXlinker("-u", symbol));
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
      return Objects.equals(pathResolver, symbolsArg.pathResolver) &&
          Objects.equals(symbolFiles, symbolsArg.symbolFiles);
    }

    @Override
    public int hashCode() {
      return Objects.hash(pathResolver, symbolFiles);
    }

    @Override
    public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
      return builder.setReflectively("symbolFiles", symbolFiles);
    }

  }

}

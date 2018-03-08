/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DelegatingTool;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A specialization of {@link Linker} containing information specific to the Windows implementation.
 */
public class WindowsLinker extends DelegatingTool implements Linker, HasImportLibrary {
  public WindowsLinker(Tool tool) {
    super(tool);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(ImmutableMap<Path, Path> cellRootMap) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> linkWhole(Arg input) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<String> soname(String arg) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> fileList(Path fileListPath) {
    return ImmutableList.of();
  }

  @Override
  public String origin() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String libOrigin() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String searchPathEnvVar() {
    return "PATH";
  }

  @Override
  public String preloadEnvVar() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableList<Arg> createUndefinedSymbolsLinkerArgs(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      Iterable<? extends SourcePath> symbolFiles) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterable<String> getNoAsNeededSharedLibsFlags() {
    return ImmutableList.of();
  }

  @Override
  public Iterable<String> getIgnoreUndefinedSymbolsFlags() {
    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> getSharedLibFlag() {
    return ImmutableList.of(StringArg.of("/DLL"));
  }

  @Override
  public Iterable<String> outputArgs(String path) {
    return ImmutableList.of("/OUT:" + path);
  }

  /**
   * https://msdn.microsoft.com/en-us/library/ts7eyw4s.aspx - LNK1104 error if Path for filename
   * expands to more than 260 characters.
   */
  @Override
  public boolean hasFilePathSizeLimitations() {
    return true;
  }

  @Override
  public SharedLibraryLoadingType getSharedLibraryLoadingType() {
    return SharedLibraryLoadingType.THE_SAME_DIRECTORY;
  }

  @Override
  public Iterable<Arg> importLibrary(Path output) {
    return StringArg.from("/IMPLIB:" + importLibraryPath(output));
  }

  @Override
  public Path importLibraryPath(Path output) {
    return Paths.get(output + ".imp.lib");
  }
}

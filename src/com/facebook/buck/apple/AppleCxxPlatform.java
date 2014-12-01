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

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DarwinLinker;
import com.facebook.buck.cxx.DebugPathSanitizer;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * An Objective-C/C/C++/Objective-C++ platform to support building iOS
 * and Mac OS X products with Xcode.
 */
public class AppleCxxPlatform implements CxxPlatform {

  private static final Path USR_BIN = Paths.get("usr/bin");

  private final Flavor flavor;

  private final SourcePath as;
  private final ImmutableList<String> asflags;
  private final SourcePath aspp;
  private final ImmutableList<String> asppflags;
  private final SourcePath cc;
  private final ImmutableList<String> cflags;
  private final SourcePath cpp;
  private final ImmutableList<String> cppflags;
  private final SourcePath cxx;
  private final ImmutableList<String> cxxflags;
  private final SourcePath cxxpp;
  private final ImmutableList<String> cxxppflags;
  private final SourcePath cxxld;
  private final ImmutableList<String> cxxldflags;
  private final SourcePath lex;
  private final ImmutableList<String> lexflags;
  private final SourcePath yacc;
  private final ImmutableList<String> yaccflags;
  private final Linker ld;
  private final ImmutableList<String> ldflags;
  private final SourcePath ar;
  private final ImmutableList<String> arflags;

  private final Optional<DebugPathSanitizer> debugPathSanitizer;

  public AppleCxxPlatform(
      Flavor flavor,
      Platform buildPlatform,
      AppleSdkPaths sdkPaths) {

    Preconditions.checkArgument(
        buildPlatform.equals(Platform.MACOS),
        String.format("%s can only currently run on Mac OS X.", AppleCxxPlatform.class));

    this.flavor = Preconditions.checkNotNull(flavor);

    // Search for tools from most specific to least specific.
    ImmutableList<Path> toolSearchPaths = ImmutableList.of(
        sdkPaths.sdkPath().resolve(USR_BIN),
        sdkPaths.platformDeveloperPath().resolve(USR_BIN),
        sdkPaths.toolchainPath().resolve(USR_BIN)
    );

    SourcePath clangPath = getTool("clang", toolSearchPaths);
    SourcePath clangXxPath = getTool("clang++", toolSearchPaths);

    this.as = clangPath;
    this.asflags = ImmutableList.of(); // TODO
    this.aspp = clangPath;
    this.asppflags = ImmutableList.of(); // TODO
    this.cc = clangPath;
    this.cflags = ImmutableList.of(); // TODO
    this.cpp = clangPath;
    this.cppflags = ImmutableList.of(); // TODO
    this.cxx = clangXxPath;
    this.cxxflags = ImmutableList.of(); // TODO
    this.cxxpp = clangXxPath;
    this.cxxppflags = ImmutableList.of(); // TODO
    this.cxxld = clangXxPath;
    this.cxxldflags = ImmutableList.of(); // TODO

    this.lex = getTool("lex", toolSearchPaths);
    this.lexflags = ImmutableList.of(); // TODO
    this.yacc = getTool("yacc", toolSearchPaths);
    this.yaccflags = ImmutableList.of(); // TODO

    this.ld = new DarwinLinker(getTool("libtool", toolSearchPaths));

    this.ldflags = ImmutableList.of(); // TODO

    this.ar = getTool("ar", toolSearchPaths);
    this.arflags = ImmutableList.of(); // TODO

    this.debugPathSanitizer =
        Optional.of(
            new DebugPathSanitizer(
                250,
                File.separatorChar,
                Paths.get("."),
                ImmutableBiMap.<Path, Path>of()));
  }

  private static SourcePath getTool(
      String tool,
      ImmutableList<Path> toolSearchPaths) {
    Optional<Path> toolPath = MoreFiles.searchPathsForExecutable(Paths.get(tool), toolSearchPaths);
    if (!toolPath.isPresent()) {
      throw new HumanReadableException(
        "Cannot find tool %s in paths %s",
        tool,
        toolSearchPaths);
    }
    return new PathSourcePath(toolPath.get());
  }

  @Override
  public Flavor asFlavor() {
    return flavor;
  }

  @Override
  public SourcePath getAs() {
    return as;
  }

  @Override
  public ImmutableList<String> getAsflags() {
    return asflags;
  }

  @Override
  public SourcePath getAspp() {
    return aspp;
  }

  @Override
  public ImmutableList<String> getAsppflags() {
    return asppflags;
  }

  @Override
  public SourcePath getCc() {
    return cc;
  }

  @Override
  public ImmutableList<String> getCflags() {
    return cflags;
  }

  @Override
  public SourcePath getCxx() {
    return cxx;
  }

  @Override
  public ImmutableList<String> getCxxflags() {
    return cxxflags;
  }

  @Override
  public SourcePath getCpp() {
    return cpp;
  }

  @Override
  public ImmutableList<String> getCppflags() {
    return cppflags;
  }

  @Override
  public SourcePath getCxxpp() {
    return cxxpp;
  }

  @Override
  public ImmutableList<String> getCxxppflags() {
    return cxxppflags;
  }

  @Override
  public SourcePath getCxxld() {
    return cxxld;
  }

  @Override
  public ImmutableList<String> getCxxldflags() {
    return cxxldflags;
  }

  @Override
  public Linker getLd() {
    return ld;
  }

  @Override
  public ImmutableList<String> getLdflags() {
    return ldflags;
  }

  @Override
  public SourcePath getAr() {
    return ar;
  }

  @Override
  public ImmutableList<String> getArflags() {
    return arflags;
  }

  @Override
  public SourcePath getLex() {
    return lex;
  }

  @Override
  public ImmutableList<String> getLexFlags() {
    return lexflags;
  }

  @Override
  public SourcePath getYacc() {
    return yacc;
  }

  @Override
  public ImmutableList<String> getYaccFlags() {
    return yaccflags;
  }

  @Override
  public String getSharedLibraryExtension() {
    return "dylib";
  }

  @Override
  public Optional<DebugPathSanitizer> getDebugPathSanitizer() {
    return debugPathSanitizer;
  }

  @Override
  public BuildTarget getGtestDep() {
    throw new HumanReadableException("gtest is not supported on %s platform", asFlavor());
  }

  @Override
  public BuildTarget getBoostTestDep() {
    throw new HumanReadableException("boost is not supported on %s platform", asFlavor());
  }
}

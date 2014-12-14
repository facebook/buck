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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A C/C++ platform described in the "cxx" section of .buckconfig, with reasonable system defaults.
 */
public class DefaultCxxPlatform implements CxxPlatform {

  private static enum LinkerType {
    GNU,
    DARWIN,
  }

  private static final Flavor FLAVOR = new Flavor("default");

  private static final Path DEFAULT_AS = Paths.get("/usr/bin/as");
  private static final ImmutableList<String> DEFAULT_ASFLAGS = ImmutableList.of();

  private static final Path DEFAULT_ASPP = Paths.get("/usr/bin/gcc");
  private static final ImmutableList<String> DEFAULT_ASPPFLAGS = ImmutableList.of();

  private static final Path DEFAULT_CC = Paths.get("/usr/bin/gcc");
  private static final ImmutableList<String> DEFAULT_CFLAGS = ImmutableList.of();

  private static final Path DEFAULT_CXX = Paths.get("/usr/bin/g++");
  private static final ImmutableList<String> DEFAULT_CXXFLAGS = ImmutableList.of();

  private static final Path DEFAULT_CPP = Paths.get("/usr/bin/gcc");
  private static final ImmutableList<String> DEFAULT_CPPFLAGS = ImmutableList.of();

  private static final Path DEFAULT_CXXPP = Paths.get("/usr/bin/g++");
  private static final ImmutableList<String> DEFAULT_CXXPPFLAGS = ImmutableList.of();

  private static final Path DEFAULT_CXXLD = Paths.get("/usr/bin/g++");
  private static final ImmutableList<String> DEFAULT_CXXLDFLAGS = ImmutableList.of();

  private static final Path DEFAULT_LD = Paths.get("/usr/bin/ld");
  private static final ImmutableList<String> DEFAULT_LDFLAGS = ImmutableList.of();

  private static final Path DEFAULT_AR = Paths.get("/usr/bin/ar");
  private static final ImmutableList<String> DEFAULT_ARFLAGS = ImmutableList.of();

  private static final Path DEFAULT_LEX = Paths.get("/usr/bin/flex");
  private static final ImmutableList<String> DEFAULT_LEX_FLAGS = ImmutableList.of();
  private static final Path DEFAULT_YACC = Paths.get("/usr/bin/bison");
  private static final ImmutableList<String> DEFAULT_YACC_FLAGS = ImmutableList.of("-y");

  private final Platform platform;
  private final BuckConfig delegate;

  private final Optional<DebugPathSanitizer> debugPathSanitizer =
      Optional.of(
          new DebugPathSanitizer(
              250,
              File.separatorChar,
              Paths.get("."),
              ImmutableBiMap.<Path, Path>of()));

  public DefaultCxxPlatform(Platform platform, BuckConfig delegate) {
    this.platform = platform;
    this.delegate = delegate;
  }

  public DefaultCxxPlatform(BuckConfig delegate) {
    this(Platform.detect(), delegate);
  }

  private ImmutableList<String> getFlags(String section, String field, ImmutableList<String> def) {
    Optional<String> value = delegate.getValue(section, field);
    if (!value.isPresent()) {
      return def;
    }
    ImmutableList.Builder<String> split = ImmutableList.builder();
    if (!value.get().trim().isEmpty()) {
      split.addAll(Splitter.on(" ").split(value.get().trim()));
    }
    return split.build();
  }

  private SourcePath getSourcePath(String section, String field, Path def) {
    Optional<Path> path = delegate.getPath(section, field);
    return new PathSourcePath(path.or(def));
  }

  private Tool getTool(String section, String field, Path def) {
    return new SourcePathTool(getSourcePath(section, field, def));
  }

  @Override
  public Flavor asFlavor() {
    return FLAVOR;
  }

  @Override
  public Tool getAs() {
    return getTool("cxx", "as", DEFAULT_AS);
  }

  @Override
  public ImmutableList<String> getAsflags() {
    return getFlags("cxx", "asflags", DEFAULT_ASFLAGS);
  }

  @Override
  public Tool getAspp() {
    return getTool("cxx", "aspp", DEFAULT_ASPP);
  }

  @Override
  public ImmutableList<String> getAsppflags() {
    return getFlags("cxx", "asppflags", DEFAULT_ASPPFLAGS);
  }

  @Override
  public Tool getCc() {
    return getTool("cxx", "cc", DEFAULT_CC);
  }

  @Override
  public ImmutableList<String> getCflags() {
    return getFlags("cxx", "cflags", DEFAULT_CFLAGS);
  }

  @Override
  public Tool getCxx() {
    return getTool("cxx", "cxx", DEFAULT_CXX);
  }

  @Override
  public ImmutableList<String> getCxxflags() {
    return getFlags("cxx", "cxxflags", DEFAULT_CXXFLAGS);
  }

  @Override
  public Tool getCpp() {
    return getTool("cxx", "cpp", DEFAULT_CPP);
  }

  @Override
  public ImmutableList<String> getCppflags() {
    return getFlags("cxx", "cppflags", DEFAULT_CPPFLAGS);
  }

  @Override
  public Tool getCxxpp() {
    return getTool("cxx", "cxxpp", DEFAULT_CXXPP);
  }

  @Override
  public ImmutableList<String> getCxxppflags() {
    return getFlags("cxx", "cxxppflags", DEFAULT_CXXPPFLAGS);
  }

  @Override
  public Tool getCxxld() {
    return getTool("cxx", "cxxld", DEFAULT_CXXLD);
  }

  @Override
  public ImmutableList<String> getCxxldflags() {
    return getFlags("cxx", "cxxldflags", DEFAULT_CXXLDFLAGS);
  }

  private LinkerType getLinkerTypeForPlatform() {
    switch (platform) {
      case LINUX:
        return LinkerType.GNU;
      case MACOS:
        return LinkerType.DARWIN;
      //$CASES-OMITTED$
      default:
        throw new HumanReadableException(
            "cannot detect linker type, try explicitly setting it in " +
            ".buckconfig's [cxx] ld_type section");
    }
  }

  private LinkerType getLinkerType() {
    Optional<LinkerType> type = delegate.getEnum("cxx", "ld_type", LinkerType.class);
    return type.or(getLinkerTypeForPlatform());
  }

  @Override
  public Linker getLd() {
    Tool tool = new SourcePathTool(getSourcePath("cxx", "ld", DEFAULT_LD));
    LinkerType type = getLinkerType();
    switch (type) {
      case GNU:
        return new GnuLinker(tool);
      case DARWIN:
        return new DarwinLinker(tool);
      // Add a "default" case, even thought we've handled all cases above, just to make the
      // compiler happy.
      default:
        throw new IllegalStateException();
    }
  }

  @Override
  public ImmutableList<String> getLdflags() {
    return getFlags("cxx", "ldflags", DEFAULT_LDFLAGS);
  }

  @Override
  public ImmutableList<String> getRuntimeLdflags(
      Linker.LinkType linkType,
      Linker.LinkableDepType linkableDepType) {
    return ImmutableList.of();
  }

  @Override
  public Tool getAr() {
    return getTool("cxx", "ar", DEFAULT_AR);
  }

  @Override
  public ImmutableList<String> getArflags() {
    return getFlags("cxx", "arflags", DEFAULT_ARFLAGS);
  }

  @Override
  public SourcePath getLex() {
    return getSourcePath("cxx", "lex", DEFAULT_LEX);
  }

  @Override
  public ImmutableList<String> getLexFlags() {
    return getFlags("cxx", "lexflags", DEFAULT_LEX_FLAGS);
  }

  @Override
  public SourcePath getYacc() {
    return getSourcePath("cxx", "yacc", DEFAULT_YACC);
  }

  @Override
  public ImmutableList<String> getYaccFlags() {
    return getFlags("cxx", "yaccflags", DEFAULT_YACC_FLAGS);
  }

  @Override
  public String getSharedLibraryExtension() {
    switch (platform) {
      case MACOS:
        return "dylib";
      case WINDOWS:
        return "dll";
      // $CASES-OMITTED$
      default:
        return "so";
    }
  }

  @Override
  public Optional<DebugPathSanitizer> getDebugPathSanitizer() {
    return debugPathSanitizer;
  }

}

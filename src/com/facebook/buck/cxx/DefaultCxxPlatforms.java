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
import com.facebook.buck.model.ImmutableFlavor;
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
 * Utility class to create a C/C++ platform described in the "cxx"
 * section of .buckconfig, with reasonable system defaults.
 */
public class DefaultCxxPlatforms {

  // Utility class, do not instantiate.
  private DefaultCxxPlatforms() { }

  private static enum LinkerType {
    DARWIN,
    GNU,
    WINDOWS,
  }

  private static final Flavor FLAVOR = ImmutableFlavor.of("default");

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

  private static final Optional<DebugPathSanitizer> DEBUG_PATH_SANITIZER =
      Optional.of(
          new DebugPathSanitizer(
              250,
              File.separatorChar,
              Paths.get("."),
              ImmutableBiMap.<Path, Path>of()));

  public static CxxPlatform build(BuckConfig delegate) {
    return build(Platform.detect(), delegate);
  }

  public static CxxPlatform build(Platform platform, BuckConfig delegate) {
    ImmutableCxxPlatform.Builder builder = ImmutableCxxPlatform.builder();
    // TODO(user, agallagher): Generalize this so we don't need all these setters.
    builder
        .setFlavor(FLAVOR)
        .setAs(getTool("cxx", "as", DEFAULT_AS, delegate))
        .addAllAsflags(getFlags("cxx", "asflags", DEFAULT_ASFLAGS, delegate))
        .setAspp(getTool("cxx", "aspp", DEFAULT_ASPP, delegate))
        .addAllAsppflags(getFlags("cxx", "asppflags", DEFAULT_ASPPFLAGS, delegate))
        .setCc(getTool("cxx", "cc", DEFAULT_CC, delegate))
        .addAllCflags(getFlags("cxx", "cflags", DEFAULT_CFLAGS, delegate))
        .setCxx(getTool("cxx", "cxx", DEFAULT_CXX, delegate))
        .addAllCxxflags(getFlags("cxx", "cxxflags", DEFAULT_CXXFLAGS, delegate))
        .setCpp(getTool("cxx", "cpp", DEFAULT_CPP, delegate))
        .addAllCppflags(getFlags("cxx", "cppflags", DEFAULT_CPPFLAGS, delegate))
        .setCxxpp(getTool("cxx", "cxxpp", DEFAULT_CXXPP, delegate))
        .addAllCxxppflags(getFlags("cxx", "cxxppflags", DEFAULT_CXXPPFLAGS, delegate))
        .setCxxld(getTool("cxx", "cxxld", DEFAULT_CXXLD, delegate))
        .addAllCxxldflags(getFlags("cxx", "cxxldflags", DEFAULT_CXXLDFLAGS, delegate))
        .setLd(getLd(platform, delegate))
        .addAllLdflags(getFlags("cxx", "ldflags", DEFAULT_LDFLAGS, delegate))
        .setAr(getTool("cxx", "ar", DEFAULT_AR, delegate))
        .addAllArflags(getFlags("cxx", "arflags", DEFAULT_ARFLAGS, delegate))
        .setLex(getSourcePath("cxx", "lex", DEFAULT_LEX, delegate))
        .addAllLexFlags(getFlags("cxx", "lexflags", DEFAULT_LEX_FLAGS, delegate))
        .setYacc(getSourcePath("cxx", "yacc", DEFAULT_YACC, delegate))
        .addAllYaccFlags(getFlags("cxx", "yaccflags", DEFAULT_YACC_FLAGS, delegate))
        .setSharedLibraryExtension(getSharedLibraryExtension(platform))
        .setDebugPathSanitizer(DEBUG_PATH_SANITIZER);
    return builder.build();
  }

  private static ImmutableList<String> getFlags(
      String section,
      String field,
      ImmutableList<String> def,
      BuckConfig delegate) {
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

  private static SourcePath getSourcePath(
      String section,
      String field,
      Path def,
      BuckConfig delegate) {
    Optional<Path> path = delegate.getPath(section, field);
    return new PathSourcePath(path.or(def));
  }

  private static Tool getTool(
      String section,
      String field,
      Path def,
      BuckConfig delegate) {
    return new SourcePathTool(getSourcePath(section, field, def, delegate));
  }

  private static LinkerType getLinkerTypeForPlatform(Platform platform) {
    switch (platform) {
      case LINUX:
        return LinkerType.GNU;
      case MACOS:
        return LinkerType.DARWIN;
      case WINDOWS:
        return LinkerType.WINDOWS;
      //$CASES-OMITTED$
      default:
        throw new HumanReadableException(
            "cannot detect linker type, try explicitly setting it in " +
            ".buckconfig's [cxx] ld_type section");
    }
  }

  private static LinkerType getLinkerType(Platform platform, BuckConfig delegate) {
    Optional<LinkerType> type = delegate.getEnum("cxx", "ld_type", LinkerType.class);
    return type.or(getLinkerTypeForPlatform(platform));
  }

  private static Linker getLd(Platform platform, BuckConfig delegate) {
    Tool tool = new SourcePathTool(getSourcePath("cxx", "ld", DEFAULT_LD, delegate));
    LinkerType type = getLinkerType(platform, delegate);
    switch (type) {
      case GNU:
        return new GnuLinker(tool);
      case DARWIN:
        return new DarwinLinker(tool);
      case WINDOWS:
        return new WindowsLinker(tool);
      // Add a "default" case, even thought we've handled all cases above, just to make the
      // compiler happy.
      default:
        throw new IllegalStateException();
    }
  }

  private static String getSharedLibraryExtension(Platform platform) {
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
}

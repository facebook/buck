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

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class to create a C/C++ platform described in the "cxx"
 * section of .buckconfig, with reasonable system defaults.
 */
public class DefaultCxxPlatforms {

  // Utility class, do not instantiate.
  private DefaultCxxPlatforms() { }

  private static final Flavor FLAVOR = ImmutableFlavor.of("default");

  private static final Path DEFAULT_AS = Paths.get("/usr/bin/as");
  private static final Path DEFAULT_C_FRONTEND = Paths.get("/usr/bin/gcc");
  private static final Path DEFAULT_CXX_FRONTEND = Paths.get("/usr/bin/g++");
  private static final Path DEFAULT_LD = Paths.get("/usr/bin/ld");
  private static final Path DEFAULT_AR = Paths.get("/usr/bin/ar");
  private static final Path DEFAULT_STRIP = Paths.get("/usr/bin/strip");
  private static final Path DEFAULT_LEX = Paths.get("/usr/bin/flex");
  private static final Path DEFAULT_YACC = Paths.get("/usr/bin/bison");

  private static final Path DEFAULT_OSX_C_FRONTEND = Paths.get("/usr/bin/clang");
  private static final Path DEFAULT_OSX_CXX_FRONTEND = Paths.get("/usr/bin/clang++");

  public static CxxPlatform build(CxxBuckConfig config) {
    return build(Platform.detect(), config);
  }

  public static CxxPlatform build(
      Platform platform,
      CxxBuckConfig config) {
    if (platform == Platform.MACOS) {
      return CxxPlatforms.build(
          FLAVOR,
          config,
          new HashedFileTool(DEFAULT_AS),
          new HashedFileTool(DEFAULT_OSX_C_FRONTEND),
          new ClangCompiler(new HashedFileTool(DEFAULT_OSX_C_FRONTEND)),
          new ClangCompiler(new HashedFileTool(DEFAULT_OSX_CXX_FRONTEND)),
          new HashedFileTool(DEFAULT_OSX_C_FRONTEND),
          new HashedFileTool(DEFAULT_OSX_CXX_FRONTEND),
          new DarwinLinker(new HashedFileTool(DEFAULT_LD)),
          new DarwinLinker(new HashedFileTool(DEFAULT_OSX_CXX_FRONTEND)),
          ImmutableList.<String>of(),
          new HashedFileTool(DEFAULT_STRIP),
          new BsdArchiver(new HashedFileTool(DEFAULT_AR)),
          ImmutableList.<String>of(),
          ImmutableList.<String>of(),
          ImmutableList.<String>of(),
          ImmutableList.<String>of(),
          Optional.<Tool>of(new HashedFileTool(DEFAULT_LEX)),
          Optional.<Tool>of(new HashedFileTool(DEFAULT_YACC)),
          "dylib",
          Optional.<DebugPathSanitizer>absent(),
          ImmutableMap.<String, String>of());
    }

    String sharedLibraryExtension;
    switch (platform) {
      case LINUX:
        sharedLibraryExtension = "so";
        break;
      case WINDOWS:
        sharedLibraryExtension = "dll";
        break;
      //$CASES-OMITTED$
      default:
        throw new RuntimeException(String.format("Unsupported platform: %s", platform));
    }

    return CxxPlatforms.build(
        FLAVOR,
        config,
        new HashedFileTool(DEFAULT_AS),
        new HashedFileTool(DEFAULT_C_FRONTEND),
        new DefaultCompiler(new HashedFileTool(DEFAULT_C_FRONTEND)),
        new DefaultCompiler(new HashedFileTool(DEFAULT_CXX_FRONTEND)),
        new HashedFileTool(DEFAULT_C_FRONTEND),
        new HashedFileTool(DEFAULT_CXX_FRONTEND),
        new GnuLinker(new HashedFileTool(DEFAULT_LD)),
        new GnuLinker(new HashedFileTool(DEFAULT_CXX_FRONTEND)),
        ImmutableList.<String>of(),
        new HashedFileTool(DEFAULT_STRIP),
        new GnuArchiver(new HashedFileTool(DEFAULT_AR)),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        Optional.<Tool>of(new HashedFileTool(DEFAULT_LEX)),
        Optional.<Tool>of(new HashedFileTool(DEFAULT_YACC)),
        sharedLibraryExtension,
        Optional.<DebugPathSanitizer>absent(),
        ImmutableMap.<String, String>of());
  }

}

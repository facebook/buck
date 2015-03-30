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
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;

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
  private static final Path DEFAULT_ASPP = Paths.get("/usr/bin/gcc");
  private static final Path DEFAULT_CC = Paths.get("/usr/bin/gcc");
  private static final Path DEFAULT_CXX = Paths.get("/usr/bin/g++");
  private static final Path DEFAULT_CPP = Paths.get("/usr/bin/gcc");
  private static final Path DEFAULT_CXXPP = Paths.get("/usr/bin/g++");
  private static final Path DEFAULT_CXXLD = Paths.get("/usr/bin/g++");
  private static final Path DEFAULT_LD = Paths.get("/usr/bin/ld");
  private static final Path DEFAULT_AR = Paths.get("/usr/bin/ar");
  private static final Path DEFAULT_LEX = Paths.get("/usr/bin/flex");
  private static final Path DEFAULT_YACC = Paths.get("/usr/bin/bison");

  public static CxxPlatform build(CxxBuckConfig config) {
    return build(Platform.detect(), config);
  }

  public static CxxPlatform build(
      Platform platform,

      CxxBuckConfig config) {
    return CxxPlatforms.build(
        FLAVOR,
        platform,
        config,
        new HashedFileTool(DEFAULT_AS),
        new HashedFileTool(DEFAULT_ASPP),
        new HashedFileTool(DEFAULT_CC),
        new HashedFileTool(DEFAULT_CXX),
        new HashedFileTool(DEFAULT_CPP),
        new HashedFileTool(DEFAULT_CXXPP),
        new HashedFileTool(DEFAULT_CXXLD),
        Optional.<CxxPlatform.LinkerType>absent(),
        new HashedFileTool(DEFAULT_LD),
        new HashedFileTool(DEFAULT_AR),
        Optional.<Tool>of(new HashedFileTool(DEFAULT_LEX)),
        Optional.<Tool>of(new HashedFileTool(DEFAULT_YACC)));
  }

}

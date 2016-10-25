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

package com.facebook.buck.cxx;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableBiMap;

import java.io.File;
import java.nio.file.Paths;

public class CxxPlatformUtils {

  private CxxPlatformUtils() {}

  public static final CxxBuckConfig DEFAULT_CONFIG =
      new CxxBuckConfig(new FakeBuckConfig.Builder().build());

  private static final Tool DEFAULT_TOOL = new CommandTool.Builder().build();

  private static final PreprocessorProvider DEFAULT_PREPROCESSOR_PROVIDER =
      new PreprocessorProvider(
          new ConstantToolProvider(DEFAULT_TOOL),
          CxxToolProvider.Type.GCC);

  private static final CompilerProvider DEFAULT_COMPILER_PROVIDER =
      new CompilerProvider(
          new ConstantToolProvider(DEFAULT_TOOL),
          CxxToolProvider.Type.GCC);

  @VisibleForTesting
  static final DebugPathSanitizer DEFAULT_DEBUG_PATH_SANITIZER =
      new MungingDebugPathSanitizer(
          250,
          File.separatorChar,
          Paths.get("."),
          ImmutableBiMap.of());

  public static final CxxPlatform DEFAULT_PLATFORM =
      CxxPlatform.builder()
          .setFlavor(ImmutableFlavor.of("platform"))
          .setAs(DEFAULT_COMPILER_PROVIDER)
          .setAspp(DEFAULT_PREPROCESSOR_PROVIDER)
          .setCc(DEFAULT_COMPILER_PROVIDER)
          .setCpp(DEFAULT_PREPROCESSOR_PROVIDER)
          .setCxx(DEFAULT_COMPILER_PROVIDER)
          .setCxxpp(DEFAULT_PREPROCESSOR_PROVIDER)
          .setCuda(DEFAULT_COMPILER_PROVIDER)
          .setCudapp(DEFAULT_PREPROCESSOR_PROVIDER)
          .setAsm(DEFAULT_COMPILER_PROVIDER)
          .setAsmpp(DEFAULT_PREPROCESSOR_PROVIDER)
          .setLd(
              new DefaultLinkerProvider(
                  LinkerProvider.Type.GNU,
                  new ConstantToolProvider(DEFAULT_TOOL)))
          .setStrip(DEFAULT_TOOL)
          .setAr(new GnuArchiver(DEFAULT_TOOL))
          .setRanlib(DEFAULT_TOOL)
          .setSymbolNameTool(new PosixNmSymbolNameTool(DEFAULT_TOOL))
          .setSharedLibraryExtension("so")
          .setSharedLibraryVersionedExtensionFormat("so.%s")
          .setStaticLibraryExtension("a")
          .setObjectFileExtension("o")
          .setDebugPathSanitizer(DEFAULT_DEBUG_PATH_SANITIZER)
          .build();

    public static final FlavorDomain<CxxPlatform> DEFAULT_PLATFORMS =
        FlavorDomain.of("C/C++ Platform", DEFAULT_PLATFORM);

  public static CxxPlatform build(CxxBuckConfig config) {
    return DefaultCxxPlatforms.build(Platform.detect(), config);
  }
}

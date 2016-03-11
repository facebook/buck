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

import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.HashedFileTool;
import com.google.common.base.Optional;

import java.nio.file.Paths;

public class CxxPlatformUtils {

  private CxxPlatformUtils() {}

  public static final CxxPlatform DEFAULT_PLATFORM =
      CxxPlatform.builder()
          .setFlavor(ImmutableFlavor.of("platform"))
          .setAs(
              new CompilerProvider(
                  Paths.get("tool"),
                  Optional.of(CxxToolProvider.Type.DEFAULT)))
          .setAspp(
              new PreprocessorProvider(
                  Paths.get("tool"),
                  Optional.of(CxxToolProvider.Type.DEFAULT)))
          .setCc(
              new CompilerProvider(
                  Paths.get("tool"),
                  Optional.of(CxxToolProvider.Type.DEFAULT)))
          .setCpp(
              new PreprocessorProvider(
                  Paths.get("tool"),
                  Optional.of(CxxToolProvider.Type.DEFAULT)))
          .setCxx(
              new CompilerProvider(
                  Paths.get("tool"),
                  Optional.of(CxxToolProvider.Type.DEFAULT)))
          .setCxxpp(
              new PreprocessorProvider(
                  Paths.get("tool"),
                  Optional.of(CxxToolProvider.Type.DEFAULT)))
          .setCuda(
              new CompilerProvider(
                  Paths.get("tool"),
                  Optional.of(CxxToolProvider.Type.DEFAULT)))
          .setCudapp(
              new PreprocessorProvider(
                  Paths.get("tool"),
                  Optional.of(CxxToolProvider.Type.DEFAULT)))
          .setLd(
              new DefaultLinkerProvider(
                  LinkerProvider.Type.GNU,
                  new ConstantToolProvider(new HashedFileTool(Paths.get("tool")))))
          .setStrip(new HashedFileTool(Paths.get("tool")))
          .setAr(new GnuArchiver(new HashedFileTool(Paths.get("tool"))))
          .setRanlib(new HashedFileTool(Paths.get("ranlib")))
          .setSymbolNameTool(new PosixNmSymbolNameTool(new HashedFileTool(Paths.get("nm"))))
          .setSharedLibraryExtension("so")
          .setSharedLibraryVersionedExtensionFormat("so.%s")
          .setDebugPathSanitizer(CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER)
          .build();

    public static final FlavorDomain<CxxPlatform> DEFAULT_PLATFORMS =
        FlavorDomain.of("C/C++ Platform", DEFAULT_PLATFORM);

}

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

import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.HashedFileTool;

import java.nio.file.Paths;

public class CxxPlatformUtils {

  private CxxPlatformUtils() {}

  public static final CxxPlatform DEFAULT_PLATFORM =
      CxxPlatform.builder()
          .setFlavor(ImmutableFlavor.of("platform"))
          .setAs(new HashedFileTool(Paths.get("tool")))
          .setAspp(new DefaultPreprocessor(new HashedFileTool(Paths.get("tool"))))
          .setCc(new DefaultCompiler(new HashedFileTool(Paths.get("tool"))))
          .setCpp(new DefaultPreprocessor(new HashedFileTool(Paths.get("tool"))))
          .setCxx(new DefaultCompiler(new HashedFileTool(Paths.get("tool"))))
          .setCxxpp(new DefaultPreprocessor(new HashedFileTool(Paths.get("tool"))))
          .setLd(new GnuLinker(new HashedFileTool(Paths.get("tool"))))
          .setStrip(new HashedFileTool(Paths.get("tool")))
          .setAr(new GnuArchiver(new HashedFileTool(Paths.get("tool"))))
          .setSharedLibraryExtension(".so")
          .setDebugPathSanitizer(CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER)
          .build();

}

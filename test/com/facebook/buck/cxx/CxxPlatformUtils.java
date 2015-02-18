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
import com.facebook.buck.rules.TestSourcePath;

public class CxxPlatformUtils {

  private CxxPlatformUtils() {}

  public static final ImmutableCxxPlatform DEFAULT_PLATFORM =
      ImmutableCxxPlatform.builder()
          .setFlavor(ImmutableFlavor.of("platform"))
          .setAs(new SourcePathTool(new TestSourcePath("tool")))
          .setAspp(new SourcePathTool(new TestSourcePath("tool")))
          .setCc(new SourcePathTool(new TestSourcePath("tool")))
          .setCpp(new SourcePathTool(new TestSourcePath("tool")))
          .setCxx(new SourcePathTool(new TestSourcePath("tool")))
          .setCxxpp(new SourcePathTool(new TestSourcePath("tool")))
          .setCxxld(new SourcePathTool(new TestSourcePath("tool")))
          .setLd(new GnuLinker(new SourcePathTool(new TestSourcePath("tool"))))
          .setAr(new SourcePathTool(new TestSourcePath("tool")))
          .setSharedLibraryExtension(".so")
          .build();

}

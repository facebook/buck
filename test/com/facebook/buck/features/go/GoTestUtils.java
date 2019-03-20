/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.go;

import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import java.nio.file.Paths;

public class GoTestUtils {

  public static final GoPlatform DEFAULT_PLATFORM =
      GoPlatform.builder()
          .setFlavor(CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR)
          .setGoOs(GoOs.LINUX)
          .setGoArch(GoArch.AMD64)
          .setGoRoot(Paths.get("/root"))
          .setCompiler(new CommandTool.Builder().build())
          .setAssembler(new CommandTool.Builder().build())
          .setLinker(new CommandTool.Builder().build())
          .setCGo(new CommandTool.Builder().build())
          .setPacker(new CommandTool.Builder().build())
          .setLinker(new CommandTool.Builder().build())
          .setCover(new CommandTool.Builder().build())
          .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
          .build();
}

/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;

public class LuaTestUtils {

  public static final LuaPlatform DEFAULT_PLATFORM =
      LuaPlatform.builder()
          .setLua(new ConstantToolProvider(new CommandTool.Builder().addArg("lua").build()))
          .setExtension(".lex")
          .setPackageStyle(AbstractLuaPlatform.PackageStyle.STANDALONE)
          .setPackager(new ConstantToolProvider(new CommandTool.Builder().build()))
          .setShouldCacheBinaries(true)
          .setNativeLinkStrategy(NativeLinkStrategy.SEPARATE)
          .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
          .build();

  public static final FlavorDomain<LuaPlatform> DEFAULT_PLATFORMS =
      FlavorDomain.of(LuaPlatform.FLAVOR_DOMAIN_NAME, DEFAULT_PLATFORM);
}

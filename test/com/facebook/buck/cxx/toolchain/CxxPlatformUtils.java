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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig.ToolType;
import com.facebook.buck.cxx.toolchain.linker.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;

public class CxxPlatformUtils {

  private CxxPlatformUtils() {}

  public static final CxxBuckConfig DEFAULT_CONFIG =
      new CxxBuckConfig(new FakeBuckConfig.Builder().build());

  private static final Tool DEFAULT_TOOL = new CommandTool.Builder().build();

  private static PreprocessorProvider defaultPreprocessorProvider(ToolType toolType) {
    return new PreprocessorProvider(
        new ConstantToolProvider(DEFAULT_TOOL), CxxToolProvider.Type.GCC, toolType);
  }

  private static CompilerProvider defaultCompilerProvider(ToolType toolType) {
    return new CompilerProvider(
        new ConstantToolProvider(DEFAULT_TOOL), CxxToolProvider.Type.GCC, toolType, false);
  }

  public static final DebugPathSanitizer DEFAULT_COMPILER_DEBUG_PATH_SANITIZER =
      new PrefixMapDebugPathSanitizer(".", ImmutableBiMap.of());

  public static final InternalFlavor DEFAULT_PLATFORM_FLAVOR = InternalFlavor.of("default");
  public static final CxxPlatform DEFAULT_PLATFORM =
      CxxPlatform.builder()
          .setFlavor(DEFAULT_PLATFORM_FLAVOR)
          .setAs(defaultCompilerProvider(ToolType.AS))
          .setAspp(defaultPreprocessorProvider(ToolType.ASPP))
          .setCc(defaultCompilerProvider(ToolType.CC))
          .setCpp(defaultPreprocessorProvider(ToolType.CPP))
          .setCxx(defaultCompilerProvider(ToolType.CXX))
          .setCxxpp(defaultPreprocessorProvider(ToolType.CXXPP))
          .setCuda(defaultCompilerProvider(ToolType.CUDA))
          .setCudapp(defaultPreprocessorProvider(ToolType.CUDAPP))
          .setAsm(defaultCompilerProvider(ToolType.ASM))
          .setAsmpp(defaultPreprocessorProvider(ToolType.ASMPP))
          .setLd(
              new DefaultLinkerProvider(
                  LinkerProvider.Type.GNU, new ConstantToolProvider(DEFAULT_TOOL), true))
          .setStrip(DEFAULT_TOOL)
          .setAr(ArchiverProvider.from(new GnuArchiver(DEFAULT_TOOL)))
          .setArchiveContents(ArchiveContents.NORMAL)
          .setRanlib(new ConstantToolProvider(DEFAULT_TOOL))
          .setSymbolNameTool(new PosixNmSymbolNameTool(DEFAULT_TOOL))
          .setSharedLibraryExtension("so")
          .setSharedLibraryVersionedExtensionFormat("so.%s")
          .setStaticLibraryExtension("a")
          .setObjectFileExtension("o")
          .setCompilerDebugPathSanitizer(DEFAULT_COMPILER_DEBUG_PATH_SANITIZER)
          .setHeaderVerification(DEFAULT_CONFIG.getHeaderVerificationOrIgnore())
          .setPublicHeadersSymlinksEnabled(true)
          .setPrivateHeadersSymlinksEnabled(true)
          .build();
  public static final UnresolvedCxxPlatform DEFAULT_UNRESOLVED_PLATFORM =
      new StaticUnresolvedCxxPlatform(DEFAULT_PLATFORM);

  public static final FlavorDomain<UnresolvedCxxPlatform> DEFAULT_PLATFORMS =
      FlavorDomain.of("C/C++ Platform", DEFAULT_UNRESOLVED_PLATFORM);

  public static CxxPlatform build(CxxBuckConfig config) {
    return DefaultCxxPlatforms.build(Platform.detect(), config);
  }

  private static CxxPlatform getDefaultPlatform(Path root) throws IOException {
    Config rawConfig = Configs.createDefaultConfig(root);
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(rawConfig.getRawConfig())
            .setFilesystem(TestProjectFilesystems.createProjectFilesystem(root))
            .setEnvironment(ImmutableMap.of())
            .build();
    return DefaultCxxPlatforms.build(Platform.detect(), new CxxBuckConfig(buckConfig));
  }

  public static HeaderMode getHeaderModeForDefaultPlatform(Path root) throws IOException {
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    CxxPlatform defaultPlatform = getDefaultPlatform(root);
    return defaultPlatform
            .getCpp()
            .resolve(ruleResolver, EmptyTargetConfiguration.INSTANCE)
            .supportsHeaderMaps()
        ? HeaderMode.SYMLINK_TREE_WITH_HEADER_MAP
        : HeaderMode.SYMLINK_TREE_ONLY;
  }
}

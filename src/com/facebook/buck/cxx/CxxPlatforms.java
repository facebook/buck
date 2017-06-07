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

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.LazyDelegatingTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

public class CxxPlatforms {

  private static final Logger LOG = Logger.get(CxxPlatforms.class);
  private static final ImmutableList<String> DEFAULT_ASFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_ASPPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CXXFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CPPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CXXPPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_LDFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_ARFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_RANLIBFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_COMPILER_ONLY_FLAGS = ImmutableList.of();

  // Utility class, do not instantiate.
  private CxxPlatforms() {}

  private static Optional<SharedLibraryInterfaceFactory> getSharedLibraryInterfaceFactory(
      CxxBuckConfig config, Platform platform) {
    Optional<SharedLibraryInterfaceFactory> sharedLibraryInterfaceFactory = Optional.empty();
    if (config.shouldUseSharedLibraryInterfaces()) {
      switch (platform) {
        case LINUX:
          sharedLibraryInterfaceFactory =
              Optional.of(
                  ElfSharedLibraryInterfaceFactory.of(config.getToolProvider("objcopy").get()));
          break;
          // $CASES-OMITTED$
        default:
      }
    }
    return sharedLibraryInterfaceFactory;
  }

  public static CxxPlatform build(
      Flavor flavor,
      Platform platform,
      final CxxBuckConfig config,
      CompilerProvider as,
      PreprocessorProvider aspp,
      CompilerProvider cc,
      CompilerProvider cxx,
      PreprocessorProvider cpp,
      PreprocessorProvider cxxpp,
      LinkerProvider ld,
      Iterable<String> ldFlags,
      Tool strip,
      final Archiver ar,
      final Tool ranlib,
      final SymbolNameTool nm,
      ImmutableList<String> asflags,
      ImmutableList<String> asppflags,
      ImmutableList<String> cflags,
      ImmutableList<String> cppflags,
      String sharedLibraryExtension,
      String sharedLibraryVersionedExtensionFormat,
      String staticLibraryExtension,
      String objectFileExtension,
      DebugPathSanitizer compilerDebugPathSanitizer,
      DebugPathSanitizer assemblerDebugPathSanitizer,
      ImmutableMap<String, String> flagMacros,
      Optional<String> binaryExtension,
      HeaderVerification headerVerification) {
    // TODO(beng, agallagher): Generalize this so we don't need all these setters.
    CxxPlatform.Builder builder = CxxPlatform.builder();

    final Archiver arDelegate =
        ar instanceof LazyDelegatingArchiver ? ((LazyDelegatingArchiver) ar).getDelegate() : ar;

    builder
        .setFlavor(flavor)
        .setAs(config.getCompilerProvider("as").orElse(as))
        .setAspp(config.getPreprocessorProvider("aspp").orElse(aspp))
        .setCc(config.getCompilerProvider("cc").orElse(cc))
        .setCxx(config.getCompilerProvider("cxx").orElse(cxx))
        .setCpp(config.getPreprocessorProvider("cpp").orElse(cpp))
        .setCxxpp(config.getPreprocessorProvider("cxxpp").orElse(cxxpp))
        .setCuda(config.getCompilerProvider("cuda"))
        .setCudapp(config.getPreprocessorProvider("cudapp"))
        .setAsm(config.getCompilerProvider("asm"))
        .setAsmpp(config.getPreprocessorProvider("asmpp"))
        .setLd(config.getLinkerProvider("ld", ld.getType()).orElse(ld))
        .addAllLdflags(ldFlags)
        .setAr(
            new LazyDelegatingArchiver(
                () ->
                    getTool("ar", config)
                        .map(getArchiver(arDelegate.getClass(), config)::apply)
                        .orElse(arDelegate)))
        .setRanlib(new LazyDelegatingTool(() -> getTool("ranlib", config).orElse(ranlib)))
        .setStrip(getTool("strip", config).orElse(strip))
        .setSharedLibraryExtension(sharedLibraryExtension)
        .setSharedLibraryVersionedExtensionFormat(sharedLibraryVersionedExtensionFormat)
        .setStaticLibraryExtension(staticLibraryExtension)
        .setObjectFileExtension(objectFileExtension)
        .setCompilerDebugPathSanitizer(compilerDebugPathSanitizer)
        .setAssemblerDebugPathSanitizer(assemblerDebugPathSanitizer)
        .setFlagMacros(flagMacros)
        .setBinaryExtension(binaryExtension)
        .setHeaderVerification(headerVerification)
        .setPublicHeadersSymlinksEnabled(config.getPublicHeadersSymlinksEnabled())
        .setPrivateHeadersSymlinksEnabled(config.getPrivateHeadersSymlinksEnabled());

    builder.setSymbolNameTool(
        new LazyDelegatingSymbolNameTool(
            () -> {
              Optional<Tool> configNm = getTool("nm", config);
              if (configNm.isPresent()) {
                return new PosixNmSymbolNameTool(configNm.get());
              } else {
                return nm;
              }
            }));

    builder.setSharedLibraryInterfaceFactory(getSharedLibraryInterfaceFactory(config, platform));

    builder.addAllCflags(cflags);
    builder.addAllCxxflags(cflags);
    builder.addAllCppflags(cppflags);
    builder.addAllCxxppflags(cppflags);
    builder.addAllAsflags(asflags);
    builder.addAllAsppflags(asppflags);
    CxxPlatforms.addToolFlagsFromConfig(config, builder);
    return builder.build();
  }

  /**
   * Creates a CxxPlatform with a defined flavor for a CxxBuckConfig with default values provided
   * from another default CxxPlatform
   */
  public static CxxPlatform copyPlatformWithFlavorAndConfig(
      CxxPlatform defaultPlatform, Platform platform, CxxBuckConfig config, Flavor flavor) {
    return CxxPlatforms.build(
        flavor,
        platform,
        config,
        defaultPlatform.getAs(),
        defaultPlatform.getAspp(),
        defaultPlatform.getCc(),
        defaultPlatform.getCxx(),
        defaultPlatform.getCpp(),
        defaultPlatform.getCxxpp(),
        defaultPlatform.getLd(),
        defaultPlatform.getLdflags(),
        defaultPlatform.getStrip(),
        defaultPlatform.getAr(),
        defaultPlatform.getRanlib(),
        defaultPlatform.getSymbolNameTool(),
        defaultPlatform.getAsflags(),
        defaultPlatform.getAsppflags(),
        defaultPlatform.getCflags(),
        defaultPlatform.getCppflags(),
        defaultPlatform.getSharedLibraryExtension(),
        defaultPlatform.getSharedLibraryVersionedExtensionFormat(),
        defaultPlatform.getStaticLibraryExtension(),
        defaultPlatform.getObjectFileExtension(),
        defaultPlatform.getCompilerDebugPathSanitizer(),
        defaultPlatform.getAssemblerDebugPathSanitizer(),
        defaultPlatform.getFlagMacros(),
        defaultPlatform.getBinaryExtension(),
        defaultPlatform.getHeaderVerification());
  }

  private static Function<Tool, Archiver> getArchiver(
      final Class<? extends Archiver> arClass, final CxxBuckConfig config) {
    return input -> {
      try {
        return config
            .getArchiver(input)
            .orElse(arClass.getConstructor(Tool.class).newInstance(input));
      } catch (ReflectiveOperationException e) {
        throw new RuntimeException(e);
      }
    };
  }

  private static ImmutableMap<String, Flavor> getHostFlavorMap() {
    // TODO(coneko): base the host flavor on architecture, too.
    return ImmutableMap.<String, Flavor>builder()
        .put(Platform.LINUX.getAutoconfName(), InternalFlavor.of("linux-x86_64"))
        .put(Platform.MACOS.getAutoconfName(), InternalFlavor.of("macosx-x86_64"))
        .put(Platform.WINDOWS.getAutoconfName(), InternalFlavor.of("windows-x86_64"))
        .put(Platform.FREEBSD.getAutoconfName(), InternalFlavor.of("freebsd-x86_64"))
        .build();
  }

  public static ImmutableSet<Flavor> getAllPossibleHostFlavors() {
    return ImmutableSet.copyOf(getHostFlavorMap().values());
  }

  public static Flavor getHostFlavor() {
    String platformName = Platform.detect().getAutoconfName();
    Flavor hostFlavor = getHostFlavorMap().get(platformName);

    if (hostFlavor == null) {
      throw new HumanReadableException("Unable to determine the host platform.");
    }
    return hostFlavor;
  }

  public static void addToolFlagsFromConfig(CxxBuckConfig config, CxxPlatform.Builder builder) {
    ImmutableList<String> asflags = config.getFlags("asflags").orElse(DEFAULT_ASFLAGS);
    ImmutableList<String> cflags = config.getFlags("cflags").orElse(DEFAULT_CFLAGS);
    ImmutableList<String> cxxflags = config.getFlags("cxxflags").orElse(DEFAULT_CXXFLAGS);
    ImmutableList<String> compilerOnlyFlags =
        config.getFlags("compiler_only_flags").orElse(DEFAULT_COMPILER_ONLY_FLAGS);

    builder
        .addAllAsflags(asflags)
        .addAllAsppflags(config.getFlags("asppflags").orElse(DEFAULT_ASPPFLAGS))
        .addAllCflags(cflags)
        .addAllCflags(compilerOnlyFlags)
        .addAllCxxflags(cxxflags)
        .addAllCxxflags(compilerOnlyFlags)
        .addAllCppflags(config.getFlags("cppflags").orElse(DEFAULT_CPPFLAGS))
        .addAllCxxppflags(config.getFlags("cxxppflags").orElse(DEFAULT_CXXPPFLAGS))
        .addAllCudaflags(config.getFlags("cudaflags").orElse(ImmutableList.of()))
        .addAllCudappflags(config.getFlags("cudappflags").orElse(ImmutableList.of()))
        .addAllAsmflags(config.getFlags("asmflags").orElse(ImmutableList.of()))
        .addAllAsmppflags(config.getFlags("asmppflags").orElse(ImmutableList.of()))
        .addAllLdflags(config.getFlags("ldflags").orElse(DEFAULT_LDFLAGS))
        .addAllArflags(config.getFlags("arflags").orElse(DEFAULT_ARFLAGS))
        .addAllRanlibflags(config.getFlags("ranlibflags").orElse(DEFAULT_RANLIBFLAGS));
  }

  public static CxxPlatform getConfigDefaultCxxPlatform(
      CxxBuckConfig cxxBuckConfig,
      ImmutableMap<Flavor, CxxPlatform> cxxPlatformsMap,
      CxxPlatform systemDefaultCxxPlatform) {
    CxxPlatform defaultCxxPlatform;
    Optional<String> defaultPlatform = cxxBuckConfig.getDefaultPlatform();
    if (defaultPlatform.isPresent()) {
      defaultCxxPlatform = cxxPlatformsMap.get(InternalFlavor.of(defaultPlatform.get()));
      if (defaultCxxPlatform == null) {
        LOG.warn(
            "Couldn't find default platform %s, falling back to system default",
            defaultPlatform.get());
      } else {
        LOG.debug("Using config default C++ platform %s", defaultCxxPlatform);
        return defaultCxxPlatform;
      }
    } else {
      LOG.debug("Using system default C++ platform %s", systemDefaultCxxPlatform);
    }

    return systemDefaultCxxPlatform;
  }

  private static Optional<Tool> getTool(String name, CxxBuckConfig config) {
    return config.getPath(name).map(HashedFileTool::new);
  }

  public static Iterable<BuildTarget> getParseTimeDeps(CxxPlatform cxxPlatform) {
    ImmutableList.Builder<BuildTarget> deps = ImmutableList.builder();
    deps.addAll(cxxPlatform.getAspp().getParseTimeDeps());
    deps.addAll(cxxPlatform.getAs().getParseTimeDeps());
    deps.addAll(cxxPlatform.getCpp().getParseTimeDeps());
    deps.addAll(cxxPlatform.getCc().getParseTimeDeps());
    deps.addAll(cxxPlatform.getCxxpp().getParseTimeDeps());
    deps.addAll(cxxPlatform.getCxx().getParseTimeDeps());
    if (cxxPlatform.getCudapp().isPresent()) {
      deps.addAll(cxxPlatform.getCudapp().get().getParseTimeDeps());
    }
    if (cxxPlatform.getCuda().isPresent()) {
      deps.addAll(cxxPlatform.getCuda().get().getParseTimeDeps());
    }
    if (cxxPlatform.getAsmpp().isPresent()) {
      deps.addAll(cxxPlatform.getAsmpp().get().getParseTimeDeps());
    }
    if (cxxPlatform.getAsm().isPresent()) {
      deps.addAll(cxxPlatform.getAsm().get().getParseTimeDeps());
    }
    deps.addAll(cxxPlatform.getLd().getParseTimeDeps());
    cxxPlatform
        .getSharedLibraryInterfaceFactory()
        .ifPresent(f -> deps.addAll(f.getParseTimeDeps()));
    return deps.build();
  }

  public static Iterable<BuildTarget> getParseTimeDeps(Iterable<CxxPlatform> cxxPlatforms) {
    ImmutableList.Builder<BuildTarget> deps = ImmutableList.builder();
    for (CxxPlatform cxxPlatform : cxxPlatforms) {
      deps.addAll(getParseTimeDeps(cxxPlatform));
    }
    return deps.build();
  }
}

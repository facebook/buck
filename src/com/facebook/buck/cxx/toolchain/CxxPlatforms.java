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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
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
  private static final ImmutableList<String> DEFAULT_CUDAFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CUDAPPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_HIPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_HIPPPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_ASMFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_ASMPPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_LDFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_ARFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_RANLIBFLAGS = ImmutableList.of();

  // Utility class, do not instantiate.
  private CxxPlatforms() {}

  private static Optional<SharedLibraryInterfaceParams> getSharedLibraryInterfaceParams(
      CxxBuckConfig config, Platform platform) {
    Optional<SharedLibraryInterfaceParams> sharedLibraryInterfaceParams = Optional.empty();
    SharedLibraryInterfaceParams.Type type = config.getSharedLibraryInterfaces();
    if (type != SharedLibraryInterfaceParams.Type.DISABLED) {
      switch (platform) {
        case LINUX:
          sharedLibraryInterfaceParams =
              Optional.of(
                  ElfSharedLibraryInterfaceParams.of(
                      config.getObjcopy().get(),
                      config.getIndependentShlibInterfacesLdflags().orElse(ImmutableList.of()),
                      type == SharedLibraryInterfaceParams.Type.DEFINED_ONLY));
          break;
          // $CASES-OMITTED$
        default:
      }
    }
    return sharedLibraryInterfaceParams;
  }

  public static CxxPlatform build(
      Flavor flavor,
      Platform platform,
      CxxBuckConfig config,
      CompilerProvider as,
      PreprocessorProvider aspp,
      CompilerProvider cc,
      CompilerProvider cxx,
      PreprocessorProvider cpp,
      PreprocessorProvider cxxpp,
      LinkerProvider ld,
      Iterable<String> ldFlags,
      ImmutableMultimap<Linker.LinkableDepType, String> runtimeLdflags,
      Tool strip,
      ArchiverProvider ar,
      ArchiveContents archiveContents,
      Optional<ToolProvider> ranlib,
      SymbolNameTool nm,
      ImmutableList<String> asflags,
      ImmutableList<String> asppflags,
      ImmutableList<String> cflags,
      ImmutableList<String> cppflags,
      ImmutableList<String> cxxflags,
      ImmutableList<String> cxxppflags,
      String sharedLibraryExtension,
      String sharedLibraryVersionedExtensionFormat,
      String staticLibraryExtension,
      String objectFileExtension,
      DebugPathSanitizer compilerDebugPathSanitizer,
      ImmutableMap<String, String> flagMacros,
      Optional<String> binaryExtension,
      HeaderVerification headerVerification,
      boolean publicHeadersSymlinksEnabled,
      boolean privateHeadersSymlinksEnabled,
      PicType picTypeForSharedLinking) {
    // TODO(beng, agallagher): Generalize this so we don't need all these setters.
    CxxPlatform.Builder builder = CxxPlatform.builder();

    if (config.getBinaryExtension().isPresent()) {
      if (config.getBinaryExtension().get().isEmpty()) {
        binaryExtension = Optional.empty();
      } else {
        binaryExtension = config.getBinaryExtension();
      }
    }

    builder
        .setFlavor(flavor)
        .setAs(config.getAs().orElse(as))
        .setAspp(config.getAspp().orElse(aspp))
        .setCc(config.getCc().orElse(cc))
        .setCxx(config.getCxx().orElse(cxx))
        .setCpp(config.getCpp().orElse(cpp))
        .setCxxpp(config.getCxxpp().orElse(cxxpp))
        .setCuda(config.getCuda())
        .setCudapp(config.getCudapp())
        .setHip(config.getHip())
        .setHippp(config.getHippp())
        .setAsm(config.getAsm())
        .setAsmpp(config.getAsmpp())
        .setLd(config.getLinkerProvider(ld.getType()).orElse(ld))
        .addAllLdflags(ldFlags)
        .setRuntimeLdflags(runtimeLdflags)
        .setAr(config.getArchiverProvider(platform).orElse(ar))
        .setRanlib(config.getRanlib().isPresent() ? config.getRanlib() : ranlib)
        .setStrip(config.getStrip().orElse(strip))
        .setBinaryExtension(binaryExtension)
        .setSharedLibraryExtension(
            config.getSharedLibraryExtension().orElse(sharedLibraryExtension))
        .setSharedLibraryVersionedExtensionFormat(sharedLibraryVersionedExtensionFormat)
        .setStaticLibraryExtension(
            config.getStaticLibraryExtension().orElse(staticLibraryExtension))
        .setObjectFileExtension(config.getObjectFileExtension().orElse(objectFileExtension))
        .setCompilerDebugPathSanitizer(compilerDebugPathSanitizer)
        .setFlagMacros(flagMacros)
        .setHeaderVerification(headerVerification)
        .setPublicHeadersSymlinksEnabled(
            config.getPublicHeadersSymlinksSetting().orElse(publicHeadersSymlinksEnabled))
        .setPrivateHeadersSymlinksEnabled(
            config.getPrivateHeadersSymlinksSetting().orElse(privateHeadersSymlinksEnabled))
        .setPicTypeForSharedLinking(picTypeForSharedLinking)
        .setConflictingHeaderBasenameWhitelist(config.getConflictingHeaderBasenameWhitelist())
        .setHeaderMode(config.getHeaderMode())
        .setUseArgFile(config.getUseArgFile())
        .setFilepathLengthLimited(config.getFilepathLengthLimited());

    builder.setSymbolNameTool(
        new LazyDelegatingSymbolNameTool(
            () -> {
              Optional<Tool> configNm = config.getNm();
              if (configNm.isPresent()) {
                return new PosixNmSymbolNameTool(configNm.get());
              } else {
                return nm;
              }
            }));

    builder.setArchiveContents(config.getArchiveContents().orElse(archiveContents));

    builder.setSharedLibraryInterfaceParams(getSharedLibraryInterfaceParams(config, platform));

    builder.addAllCflags(cflags);
    builder.addAllCxxflags(cxxflags);
    builder.addAllCppflags(cppflags);
    builder.addAllCxxppflags(cxxppflags);
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
        defaultPlatform.getRuntimeLdflags(),
        defaultPlatform.getStrip(),
        defaultPlatform.getAr(),
        defaultPlatform.getArchiveContents(),
        defaultPlatform.getRanlib(),
        defaultPlatform.getSymbolNameTool(),
        defaultPlatform.getAsflags(),
        defaultPlatform.getAsppflags(),
        defaultPlatform.getCflags(),
        defaultPlatform.getCppflags(),
        defaultPlatform.getCxxflags(),
        defaultPlatform.getCxxppflags(),
        defaultPlatform.getSharedLibraryExtension(),
        defaultPlatform.getSharedLibraryVersionedExtensionFormat(),
        defaultPlatform.getStaticLibraryExtension(),
        defaultPlatform.getObjectFileExtension(),
        defaultPlatform.getCompilerDebugPathSanitizer(),
        defaultPlatform.getFlagMacros(),
        defaultPlatform.getBinaryExtension(),
        defaultPlatform.getHeaderVerification(),
        defaultPlatform.getPublicHeadersSymlinksEnabled(),
        defaultPlatform.getPrivateHeadersSymlinksEnabled(),
        defaultPlatform.getPicTypeForSharedLinking());
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
    builder
        .addAllAsflags(config.getAsflags().orElse(DEFAULT_ASFLAGS))
        .addAllAsppflags(config.getAsppflags().orElse(DEFAULT_ASPPFLAGS))
        .addAllCflags(config.getCflags().orElse(DEFAULT_CFLAGS))
        .addAllCxxflags(config.getCxxflags().orElse(DEFAULT_CXXFLAGS))
        .addAllCppflags(config.getCppflags().orElse(DEFAULT_CPPFLAGS))
        .addAllCxxppflags(config.getCxxppflags().orElse(DEFAULT_CXXPPFLAGS))
        .addAllCudaflags(config.getCudaflags().orElse(DEFAULT_CUDAFLAGS))
        .addAllCudappflags(config.getCudappflags().orElse(DEFAULT_CUDAPPFLAGS))
        .addAllHipflags(config.getHipflags().orElse(DEFAULT_HIPFLAGS))
        .addAllHipppflags(config.getHipppflags().orElse(DEFAULT_HIPPPFLAGS))
        .addAllAsmflags(config.getAsmflags().orElse(DEFAULT_ASMFLAGS))
        .addAllAsmppflags(config.getAsmppflags().orElse(DEFAULT_ASMPPFLAGS))
        .addAllLdflags(config.getLdflags().orElse(DEFAULT_LDFLAGS))
        .addAllArflags(config.getArflags().orElse(DEFAULT_ARFLAGS))
        .addAllRanlibflags(config.getRanlibflags().orElse(DEFAULT_RANLIBFLAGS));
  }

  /** Returns the configured default cxx platform. */
  public static UnresolvedCxxPlatform getConfigDefaultCxxPlatform(
      CxxBuckConfig cxxBuckConfig,
      ImmutableMap<Flavor, UnresolvedCxxPlatform> cxxPlatformsMap,
      UnresolvedCxxPlatform systemDefaultCxxPlatform) {
    UnresolvedCxxPlatform defaultCxxPlatform;
    Optional<String> defaultPlatform = cxxBuckConfig.getDefaultPlatform();
    if (defaultPlatform.isPresent()) {
      defaultCxxPlatform = cxxPlatformsMap.get(InternalFlavor.of(defaultPlatform.get()));
      if (defaultCxxPlatform == null) {
        LOG.info(
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

  public static Iterable<BuildTarget> getParseTimeDeps(
      TargetConfiguration targetConfiguration, CxxPlatform cxxPlatform) {
    ImmutableList.Builder<BuildTarget> deps = ImmutableList.builder();
    deps.addAll(cxxPlatform.getAspp().getParseTimeDeps(targetConfiguration));
    deps.addAll(cxxPlatform.getAs().getParseTimeDeps(targetConfiguration));
    deps.addAll(cxxPlatform.getCpp().getParseTimeDeps(targetConfiguration));
    deps.addAll(cxxPlatform.getCc().getParseTimeDeps(targetConfiguration));
    deps.addAll(cxxPlatform.getCxxpp().getParseTimeDeps(targetConfiguration));
    deps.addAll(cxxPlatform.getCxx().getParseTimeDeps(targetConfiguration));
    if (cxxPlatform.getCudapp().isPresent()) {
      deps.addAll(cxxPlatform.getCudapp().get().getParseTimeDeps(targetConfiguration));
    }
    if (cxxPlatform.getCuda().isPresent()) {
      deps.addAll(cxxPlatform.getCuda().get().getParseTimeDeps(targetConfiguration));
    }
    if (cxxPlatform.getHippp().isPresent()) {
      deps.addAll(cxxPlatform.getHippp().get().getParseTimeDeps(targetConfiguration));
    }
    if (cxxPlatform.getHip().isPresent()) {
      deps.addAll(cxxPlatform.getHip().get().getParseTimeDeps(targetConfiguration));
    }
    if (cxxPlatform.getAsmpp().isPresent()) {
      deps.addAll(cxxPlatform.getAsmpp().get().getParseTimeDeps(targetConfiguration));
    }
    if (cxxPlatform.getAsm().isPresent()) {
      deps.addAll(cxxPlatform.getAsm().get().getParseTimeDeps(targetConfiguration));
    }
    deps.addAll(cxxPlatform.getLd().getParseTimeDeps(targetConfiguration));
    deps.addAll(cxxPlatform.getAr().getParseTimeDeps(targetConfiguration));
    if (cxxPlatform.getRanlib().isPresent()) {
      deps.addAll(cxxPlatform.getRanlib().get().getParseTimeDeps(targetConfiguration));
    }
    cxxPlatform
        .getSharedLibraryInterfaceParams()
        .ifPresent(f -> deps.addAll(f.getParseTimeDeps(targetConfiguration)));
    return deps.build();
  }

  /** Returns the configured cxx platform for a particular target. */
  public static UnresolvedCxxPlatform getCxxPlatform(
      CxxPlatformsProvider cxxPlatformsProvider,
      BuildTarget target,
      Optional<Flavor> defaultCxxPlatformFlavor) {

    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProvider.getUnresolvedCxxPlatforms();

    // First check if the build target is setting a particular target.
    Optional<UnresolvedCxxPlatform> targetPlatform = cxxPlatforms.getValue(target.getFlavors());
    if (targetPlatform.isPresent()) {
      return targetPlatform.get();
    }

    // Next, check for a constructor arg level default platform.
    if (defaultCxxPlatformFlavor.isPresent()) {
      return cxxPlatforms.getValue(defaultCxxPlatformFlavor.get());
    }

    // Otherwise, fallback to the description-level default platform.
    return cxxPlatforms.getValue(
        cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform().getFlavor());
  }

  public static Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      CxxPlatformsProvider cxxPlatformsProvider,
      BuildTarget buildTarget,
      Optional<Flavor> defaultCxxPlatformFlavor) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    // Get any parse time deps from the C/C++ platforms.
    deps.addAll(
        getCxxPlatform(cxxPlatformsProvider, buildTarget, defaultCxxPlatformFlavor)
            .getParseTimeDeps(buildTarget.getTargetConfiguration()));

    return deps.build();
  }
}

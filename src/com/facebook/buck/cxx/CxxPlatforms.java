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
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxPlatforms {

  private static final Logger LOG = Logger.get(CxxPlatforms.class);
  private static final ImmutableList<String> DEFAULT_ASFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_ASPPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CXXFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CPPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CXXPPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CXXLDFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_LDFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_ARFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_LEX_FLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_YACC_FLAGS = ImmutableList.of("-y");

  @VisibleForTesting
  static final DebugPathSanitizer DEFAULT_DEBUG_PATH_SANITIZER =
      new DebugPathSanitizer(
          250,
          File.separatorChar,
          Paths.get("."),
          ImmutableBiMap.<Path, Path>of());


  // Utility class, do not instantiate.
  private CxxPlatforms() { }

  public static CxxPlatform build(
      Flavor flavor,
      Platform platform,
      CxxBuckConfig config,
      Tool as,
      Tool aspp,
      Tool cc,
      Tool cxx,
      Tool cpp,
      Tool cxxpp,
      Tool cxxld,
      Optional<CxxPlatform.LinkerType> linkerType,
      Tool ld,
      Iterable<String> ldFlags,
      Tool ar,
      byte[] expectedGlobalHeader,
      ImmutableList<String> cflags,
      ImmutableList<String> cppflags,
      Optional<Tool> lex,
      Optional<Tool> yacc,
      Optional<DebugPathSanitizer> debugPathSanitizer) {
    // TODO(user, agallagher): Generalize this so we don't need all these setters.
    CxxPlatform.Builder builder = CxxPlatform.builder();

    builder
        .setFlavor(flavor)
        .setAs(getTool(flavor, "as", config).or(as))
        .setAspp(getTool(flavor, "aspp", config).or(aspp))
        .setCc(getTool(flavor, "cc", config).or(cc))
        .setCxx(getTool(flavor, "cxx", config).or(cxx))
        .setCpp(getTool(flavor, "cpp", config).or(cpp))
        .setCxxpp(getTool(flavor, "cxxpp", config).or(cxxpp))
        .setCxxld(getTool(flavor, "cxxld", config).or(cxxld))
        .setLd(getLd(flavor, platform, config, linkerType, getTool(flavor, "ld", config).or(ld)))
        .addAllLdflags(ldFlags)
        .setAr(getTool(flavor, "ar", config).or(ar))
        .setArExpectedGlobalHeader(expectedGlobalHeader)
        .setLex(getTool(flavor, "lex", config).or(lex))
        .setYacc(getTool(flavor, "yacc", config).or(yacc))
        .setSharedLibraryExtension(CxxPlatforms.getSharedLibraryExtension(platform))
        .setDebugPathSanitizer(debugPathSanitizer.or(CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER));
    builder.addAllCflags(cflags);
    builder.addAllCxxflags(cflags);
    builder.addAllCppflags(cppflags);
    builder.addAllCxxppflags(cppflags);
    builder.addAllCxxldflags(cflags);
    CxxPlatforms.addToolFlagsFromConfig(config, builder);
    return builder.build();
  }

  /**
   * Creates a CxxPlatform with a defined flavor for a CxxBuckConfig with default values
   * provided from another default CxxPlatform
   */
  public static CxxPlatform copyPlatformWithFlavorAndConfig(
    CxxPlatform defaultPlatform,
    CxxBuckConfig config,
    Flavor flavor
  ) {
    Platform platform = Platform.detect();
    Optional<CxxPlatform.LinkerType> linkerType = Optional.absent();
    CxxPlatform.Builder builder = CxxPlatform.builder();
    builder
      .setFlavor(flavor)
      .setAs(getTool(flavor, "as", config).or(defaultPlatform.getAs()))
      .setAspp(getTool(flavor, "aspp", config).or(defaultPlatform.getAspp()))
      .setCc(getTool(flavor, "cc", config).or(defaultPlatform.getCc()))
      .setCxx(getTool(flavor, "cxx", config).or(defaultPlatform.getCxx()))
      .setCpp(getTool(flavor, "cpp", config).or(defaultPlatform.getCpp()))
      .setCxxpp(getTool(flavor, "cxxpp", config).or(defaultPlatform.getCxxpp()))
      .setCxxld(getTool(flavor, "cxxld", config).or(defaultPlatform.getCxxld()))
      .setLd(
        getLd(
          flavor, platform, config, linkerType, getTool(flavor, "ld", config)
            .or(defaultPlatform.getLd().getTool())
        )
      )
      .setAr(getTool(flavor, "ar", config).or(defaultPlatform.getAr()))
      .setArExpectedGlobalHeader(defaultPlatform.getArExpectedGlobalHeader())
      .setLex(getTool(flavor, "lex", config).or(defaultPlatform.getLex()))
      .setYacc(getTool(flavor, "yacc", config).or(defaultPlatform.getYacc()))
      .setSharedLibraryExtension(CxxPlatforms.getSharedLibraryExtension(platform))
      .setDebugPathSanitizer(defaultPlatform.getDebugPathSanitizer());

    if (config.getDefaultPlatform().isPresent()) {
      // Try to add the tool flags from the default platform
      CxxPlatforms.addToolFlagsFromCxxPlatform(builder, defaultPlatform);
    }
    CxxPlatforms.addToolFlagsFromConfig(config, builder);
    return builder.build();
  }

  private static String getSharedLibraryExtension(Platform platform) {
    switch (platform) {
      case MACOS:
        return "dylib";
      case WINDOWS:
        return "dll";
      // $CASES-OMITTED$
      default:
        return "so";
    }
  }

  private static Linker getLd(
      Flavor flavor,
      Platform platform,
      CxxBuckConfig config,
      Optional<CxxPlatform.LinkerType> linkerType,
      Tool tool) {
    CxxPlatform.LinkerType type = config
        .getLinkerType(flavor.toString(), CxxPlatform.LinkerType.class)
        .or(linkerType)
        .or(getLinkerTypeForPlatform(platform));
    switch (type) {
      case GNU:
        return new GnuLinker(tool);
      case DARWIN:
        return new DarwinLinker(tool);
      case WINDOWS:
        return new WindowsLinker(tool);
    }
    throw new IllegalStateException();
  }

  private static CxxPlatform.LinkerType getLinkerTypeForPlatform(Platform platform) {
    switch (platform) {
      case LINUX:
        return CxxPlatform.LinkerType.GNU;
      case MACOS:
        return CxxPlatform.LinkerType.DARWIN;
      case WINDOWS:
        return CxxPlatform.LinkerType.WINDOWS;
      //$CASES-OMITTED$
      default:
        throw new HumanReadableException(
            "cannot detect linker type, try explicitly setting it in " +
            ".buckconfig's [cxx] ld_type section");
    }
  }

  public static void addToolFlagsFromConfig(
      CxxBuckConfig config,
      CxxPlatform.Builder builder) {
    ImmutableList<String> asflags = config.getFlags("asflags").or(DEFAULT_ASFLAGS);
    ImmutableList<String> cflags = config.getFlags("cflags").or(DEFAULT_CFLAGS);
    ImmutableList<String> cxxflags = config.getFlags("cxxflags").or(DEFAULT_CXXFLAGS);
    builder
        .addAllAsflags(asflags)
        .addAllAsppflags(config.getFlags("asppflags").or(DEFAULT_ASPPFLAGS))
        .addAllAsppflags(asflags)
        .addAllCflags(cflags)
        .addAllCxxflags(cxxflags)
        .addAllCppflags(config.getFlags("cppflags").or(DEFAULT_CPPFLAGS))
        .addAllCppflags(cflags)
        .addAllCxxppflags(config.getFlags("cxxppflags").or(DEFAULT_CXXPPFLAGS))
        .addAllCxxppflags(cxxflags)
        .addAllCxxldflags(config.getFlags("cxxldflags").or(DEFAULT_CXXLDFLAGS))
        .addAllLdflags(config.getFlags("ldflags").or(DEFAULT_LDFLAGS))
        .addAllArflags(config.getFlags("arflags").or(DEFAULT_ARFLAGS))
        .addAllLexFlags(config.getFlags("lexflags").or(DEFAULT_LEX_FLAGS))
        .addAllYaccFlags(config.getFlags("yaccflags").or(DEFAULT_YACC_FLAGS));
  }

  public static void addToolFlagsFromCxxPlatform(
    CxxPlatform.Builder builder,
    CxxPlatform platform) {
    builder
        .addAllAsflags(platform.getAsflags())
        .addAllAsppflags(platform.getAsppflags())
        .addAllAsppflags(platform.getAsflags())
        .addAllCflags(platform.getCflags())
        .addAllCxxflags(platform.getCxxflags())
        .addAllCppflags(platform.getCppflags())
        .addAllCppflags(platform.getCflags())
        .addAllCxxppflags(platform.getCxxflags())
        .addAllCxxppflags(platform.getCxxppflags())
        .addAllCxxldflags(platform.getCxxldflags())
        .addAllLdflags(platform.getLdflags())
        .addAllArflags(platform.getArflags())
        .addAllLexFlags(platform.getLexFlags())
        .addAllYaccFlags(platform.getYaccFlags());
  }

  public static CxxPlatform getConfigDefaultCxxPlatform(
      CxxBuckConfig cxxBuckConfig,
      ImmutableMap<Flavor, CxxPlatform> cxxPlatformsMap,
      CxxPlatform systemDefaultCxxPlatform) {
    CxxPlatform defaultCxxPlatform;
    Optional<String> defaultPlatform = cxxBuckConfig.getDefaultPlatform();
    if (defaultPlatform.isPresent()) {
      defaultCxxPlatform = cxxPlatformsMap.get(
          ImmutableFlavor.of(defaultPlatform.get()));
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

  private static Optional<Tool> getTool(Flavor flavor, String name, CxxBuckConfig config) {
    return config
        .getPath(flavor.toString(), name)
        .transform(HashedFileTool.FROM_PATH)
        .transform(Functions.<Tool>identity());
  }

}

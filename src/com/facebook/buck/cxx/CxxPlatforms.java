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
import com.facebook.buck.rules.Tool;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
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
  private static final ImmutableList<String> DEFAULT_COMPILER_ONLY_FLAGS = ImmutableList.of();

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
      CxxBuckConfig config,
      Tool as,
      Tool aspp,
      Compiler cc,
      Compiler cxx,
      Tool cpp,
      Tool cxxpp,
      Linker ld,
      Linker cxxld,
      Iterable<String> ldFlags,
      Tool strip,
      Archiver ar,
      ImmutableList<String> asflags,
      ImmutableList<String> asppflags,
      ImmutableList<String> cflags,
      ImmutableList<String> cppflags,
      Optional<Tool> lex,
      Optional<Tool> yacc,
      String sharedLibraryExtension,
      Optional<DebugPathSanitizer> debugPathSanitizer,
      ImmutableMap<String, String> flagMacros) {
    // TODO(user, agallagher): Generalize this so we don't need all these setters.
    CxxPlatform.Builder builder = CxxPlatform.builder();

    builder
        .setFlavor(flavor)
        .setAs(getTool(flavor, "as", config).or(as))
        .setAspp(getTool(flavor, "aspp", config).or(aspp))
        // TODO(user): Don't assume the compiler override specifies the same type of compiler as
        // the default one.
        .setCc(getTool(flavor, "cc", config).transform(getCompiler(cc.getClass())).or(cc))
        .setCxx(getTool(flavor, "cxx", config).transform(getCompiler(cxx.getClass())).or(cxx))
        .setCpp(getTool(flavor, "cpp", config).or(cpp))
        .setCxxpp(getTool(flavor, "cxxpp", config).or(cxxpp))
        .setCxxld(getTool(flavor, "cxxld", config).transform(getLinker(cxxld.getClass())).or(cxxld))
        .setLd(getTool(flavor, "ld", config).transform(getLinker(ld.getClass())).or(ld))
        .addAllLdflags(ldFlags)
        .setAr(getTool(flavor, "ar", config).transform(getArchiver(ar.getClass())).or(ar))
        .setStrip(getTool(flavor, "strip", config).or(strip))
        .setLex(getTool(flavor, "lex", config).or(lex))
        .setYacc(getTool(flavor, "yacc", config).or(yacc))
        .setSharedLibraryExtension(sharedLibraryExtension)
        .setDebugPathSanitizer(debugPathSanitizer.or(CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER))
        .setFlagMacros(flagMacros);
    builder.addAllCflags(cflags);
    builder.addAllCxxflags(cflags);
    builder.addAllCppflags(cppflags);
    builder.addAllCxxppflags(cppflags);
    builder.addAllCxxldflags(cflags);
    builder.addAllAsflags(asflags);
    builder.addAllAsppflags(asppflags);
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
    CxxPlatform.Builder builder = CxxPlatform.builder();
    builder
      .setFlavor(flavor)
      .setAs(getTool(flavor, "as", config).or(defaultPlatform.getAs()))
      .setAspp(getTool(flavor, "aspp", config).or(defaultPlatform.getAspp()))
      .setCc(
          getTool(flavor, "cc", config)
              .transform(getCompiler(defaultPlatform.getCc().getClass()))
              .or(defaultPlatform.getCc()))
      .setCxx(
          getTool(flavor, "cxx", config)
              .transform(getCompiler(defaultPlatform.getCxx().getClass()))
              .or(defaultPlatform.getCxx()))
      .setCpp(getTool(flavor, "cpp", config).or(defaultPlatform.getCpp()))
      .setCxxpp(getTool(flavor, "cxxpp", config).or(defaultPlatform.getCxxpp()))
      .setCxxld(
          getTool(flavor, "cxxld", config)
              .transform(getLinker(defaultPlatform.getCxxld().getClass()))
              .or(defaultPlatform.getCxxld()))
      .setLd(
          getTool(flavor, "ld", config)
              .transform(getLinker(defaultPlatform.getLd().getClass()))
              .or(defaultPlatform.getLd()))
      .setAr(new GnuArchiver(getTool(flavor, "ar", config).or(defaultPlatform.getAr())))
      .setStrip(getTool(flavor, "strip", config).or(defaultPlatform.getStrip()))
      .setLex(getTool(flavor, "lex", config).or(defaultPlatform.getLex()))
      .setYacc(getTool(flavor, "yacc", config).or(defaultPlatform.getYacc()))
      .setSharedLibraryExtension(defaultPlatform.getSharedLibraryExtension())
      .setDebugPathSanitizer(defaultPlatform.getDebugPathSanitizer());

    if (config.getDefaultPlatform().isPresent()) {
      // Try to add the tool flags from the default platform
      CxxPlatforms.addToolFlagsFromCxxPlatform(builder, defaultPlatform);
    }
    CxxPlatforms.addToolFlagsFromConfig(config, builder);
    return builder.build();
  }

  private static Function<Tool, Compiler> getCompiler(final Class<? extends Compiler> ccClass) {
    return new Function<Tool, Compiler>() {
      @Override
      public Compiler apply(Tool input) {
        try {
          return ccClass.getConstructor(Tool.class).newInstance(input);
        } catch (ReflectiveOperationException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  private static Function<Tool, Archiver> getArchiver(final Class<? extends Archiver> arClass) {
    return new Function<Tool, Archiver>() {
      @Override
      public Archiver apply(Tool input) {
        try {
          return arClass.getConstructor(Tool.class).newInstance(input);
        } catch (ReflectiveOperationException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  private static Function<Tool, Linker> getLinker(final Class<? extends Linker> ldClass) {
    return new Function<Tool, Linker>() {
      @Override
      public Linker apply(Tool input) {
        try {
          return ldClass.getConstructor(Tool.class).newInstance(input);
        } catch (ReflectiveOperationException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  public static void addToolFlagsFromConfig(
      CxxBuckConfig config,
      CxxPlatform.Builder builder) {
    ImmutableList<String> asflags = config.getFlags("asflags").or(DEFAULT_ASFLAGS);
    ImmutableList<String> cflags = config.getFlags("cflags").or(DEFAULT_CFLAGS);
    ImmutableList<String> cxxflags = config.getFlags("cxxflags").or(DEFAULT_CXXFLAGS);
    ImmutableList<String> compilerOnlyFlags = config.getFlags("compiler_only_flags")
        .or(DEFAULT_COMPILER_ONLY_FLAGS);

    builder
        .addAllAsflags(asflags)
        .addAllAsppflags(config.getFlags("asppflags").or(DEFAULT_ASPPFLAGS))
        .addAllAsppflags(asflags)
        .addAllCflags(cflags)
        .addAllCflags(compilerOnlyFlags)
        .addAllCxxflags(cxxflags)
        .addAllCxxflags(compilerOnlyFlags)
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

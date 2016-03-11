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
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.HashedFileTool;
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
  private static final ImmutableList<String> DEFAULT_LDFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_ARFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_RANLIBFLAGS = ImmutableList.of();
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
      CompilerProvider as,
      PreprocessorProvider aspp,
      CompilerProvider cc,
      CompilerProvider cxx,
      PreprocessorProvider cpp,
      PreprocessorProvider cxxpp,
      Linker ld,
      Iterable<String> ldFlags,
      Tool strip,
      Archiver ar,
      Tool ranlib,
      Tool nm,
      ImmutableList<String> asflags,
      ImmutableList<String> asppflags,
      ImmutableList<String> cflags,
      ImmutableList<String> cppflags,
      String sharedLibraryExtension,
      String sharedLibraryVersionedExtensionFormat,
      Optional<DebugPathSanitizer> debugPathSanitizer,
      ImmutableMap<String, String> flagMacros) {
    // TODO(bhamiltoncx, andrewjcg): Generalize this so we don't need all these setters.
    CxxPlatform.Builder builder = CxxPlatform.builder();

    builder
        .setFlavor(flavor)
        // Always use `DEFAULT` for the assemblers (unless an explicit override is set in the
        // .buckconfig), as we pass special flags when we detect clang which causes unused flag
        // warnings with assembling.
        .setAs(config.getCompilerProvider(flavor, "as", CxxToolProvider.Type.DEFAULT).or(as))
        .setAspp(
            config.getPreprocessorProvider(flavor, "aspp", CxxToolProvider.Type.DEFAULT).or(aspp))
        .setCc(config.getCompilerProvider(flavor, "cc").or(cc))
        .setCxx(config.getCompilerProvider(flavor, "cxx").or(cxx))
        .setCpp(config.getPreprocessorProvider(flavor, "cpp").or(cpp))
        .setCxxpp(config.getPreprocessorProvider(flavor, "cxxpp").or(cxxpp))
        .setCuda(config.getCompilerProvider(flavor, "cuda"))
        .setCudapp(config.getPreprocessorProvider(flavor, "cudapp"))
        .setLd(getTool(flavor, "ld", config).transform(getLinker(ld.getClass(), config)).or(ld))
        .addAllLdflags(ldFlags)
        .setAr(getTool(flavor, "ar", config).transform(getArchiver(ar.getClass(), config)).or(ar))
        .setRanlib(getTool(flavor, "ranlib", config).or(ranlib))
        .setStrip(getTool(flavor, "strip", config).or(strip))
        .setSymbolNameTool(new PosixNmSymbolNameTool(getTool(flavor, "nm", config).or(nm)))
        .setSharedLibraryExtension(sharedLibraryExtension)
        .setSharedLibraryVersionedExtensionFormat(sharedLibraryVersionedExtensionFormat)
        .setDebugPathSanitizer(debugPathSanitizer.or(CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER))
        .setFlagMacros(flagMacros);
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
        .setAs(
            config.getCompilerProvider(flavor, "as", CxxToolProvider.Type.DEFAULT)
                .or(defaultPlatform.getAs()))
        .setAspp(
            config.getPreprocessorProvider(flavor, "aspp", CxxToolProvider.Type.DEFAULT)
                .or(defaultPlatform.getAspp()))
        .setCc(config.getCompilerProvider(flavor, "cc").or(defaultPlatform.getCc()))
        .setCxx(config.getCompilerProvider(flavor, "cxx").or(defaultPlatform.getCxx()))
        .setCpp(config.getPreprocessorProvider(flavor, "cpp").or(defaultPlatform.getCpp()))
        .setCxxpp(config.getPreprocessorProvider(flavor, "cxxpp").or(defaultPlatform.getCxxpp()))
        .setCuda(config.getCompilerProvider(flavor, "cuda").or(defaultPlatform.getCuda()))
        .setCudapp(config.getPreprocessorProvider(flavor, "cudapp").or(defaultPlatform.getCudapp()))
        .setLd(
            getTool(flavor, "ld", config)
                .transform(getLinker(defaultPlatform.getLd().getClass(), config))
                .or(defaultPlatform.getLd()))
        .setAr(getTool(flavor, "ar", config)
                .transform(getArchiver(defaultPlatform.getAr().getClass(), config))
                .or(defaultPlatform.getAr()))
        .setRanlib(getTool(flavor, "ranlib", config).or(defaultPlatform.getRanlib()))
        .setStrip(getTool(flavor, "strip", config).or(defaultPlatform.getStrip()))
        .setSymbolNameTool(defaultPlatform.getSymbolNameTool())
        .setSharedLibraryExtension(defaultPlatform.getSharedLibraryExtension())
        .setSharedLibraryVersionedExtensionFormat(
            defaultPlatform.getSharedLibraryVersionedExtensionFormat())
        .setDebugPathSanitizer(defaultPlatform.getDebugPathSanitizer());

    if (config.getDefaultPlatform().isPresent()) {
      // Try to add the tool flags from the default platform
      CxxPlatforms.addToolFlagsFromCxxPlatform(builder, defaultPlatform);
    }
    CxxPlatforms.addToolFlagsFromConfig(config, builder);
    return builder.build();
  }

  private static Function<Tool, Archiver> getArchiver(final Class<? extends Archiver> arClass,
      final CxxBuckConfig config) {
    return new Function<Tool, Archiver>() {
      @Override
      public Archiver apply(Tool input) {
        try {
          return config.getArchiver(input)
              .or(arClass.getConstructor(Tool.class).newInstance(input));
        } catch (ReflectiveOperationException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  private static Function<Tool, Linker> getLinker(final Class<? extends Linker> ldClass,
      final CxxBuckConfig config) {
    return new Function<Tool, Linker>() {
      @Override
      public Linker apply(Tool input) {
        try {
          return config.getLinker(input).or(ldClass.getConstructor(Tool.class).newInstance(input));
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
        .addAllCflags(cflags)
        .addAllCflags(compilerOnlyFlags)
        .addAllCxxflags(cxxflags)
        .addAllCxxflags(compilerOnlyFlags)
        .addAllCppflags(config.getFlags("cppflags").or(DEFAULT_CPPFLAGS))
        .addAllCxxppflags(config.getFlags("cxxppflags").or(DEFAULT_CXXPPFLAGS))
        .addAllCudaflags(config.getFlags("cudaflags").or(ImmutableList.<String>of()))
        .addAllCudappflags(config.getFlags("cudappflags").or(ImmutableList.<String>of()))
        .addAllLdflags(config.getFlags("ldflags").or(DEFAULT_LDFLAGS))
        .addAllArflags(config.getFlags("arflags").or(DEFAULT_ARFLAGS))
        .addAllRanlibflags(config.getFlags("ranlibflags").or(DEFAULT_RANLIBFLAGS));
  }

  public static void addToolFlagsFromCxxPlatform(
    CxxPlatform.Builder builder,
    CxxPlatform platform) {
    builder
        .addAllAsflags(platform.getAsflags())
        .addAllAsppflags(platform.getAsppflags())
        .addAllCflags(platform.getCflags())
        .addAllCxxflags(platform.getCxxflags())
        .addAllCppflags(platform.getCppflags())
        .addAllCxxppflags(platform.getCxxppflags())
        .addAllLdflags(platform.getLdflags())
        .addAllArflags(platform.getArflags())
        .addAllRanlibflags(platform.getRanlibflags());
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

  private static Optional<Path> getToolPath(Flavor flavor, String name, CxxBuckConfig config) {
    return config.getPath(flavor.toString(), name);
  }

  private static Optional<Tool> getTool(Flavor flavor, String name, CxxBuckConfig config) {
    return getToolPath(flavor, name, config)
        .transform(HashedFileTool.FROM_PATH)
        .transform(Functions.<Tool>identity());
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

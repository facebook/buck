/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.toolprovider.impl.ToolProviders;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.cxx.toolchain.ArchiverProvider;
import com.facebook.buck.cxx.toolchain.CompilerProvider;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig.ToolType;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxToolProvider;
import com.facebook.buck.cxx.toolchain.ElfSharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderVerification;
import com.facebook.buck.cxx.toolchain.PosixNmSymbolNameTool;
import com.facebook.buck.cxx.toolchain.PreprocessorProvider;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceParams.Type;
import com.facebook.buck.cxx.toolchain.linker.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Defines a cxx_toolchain rule that allows a {@link CxxPlatform} to be configured as a build
 * target.
 */
public class CxxToolchainDescription
    implements DescriptionWithTargetGraph<CxxToolchainDescriptionArg> {

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CxxToolchainDescriptionArg args) {
    BuildRuleResolver ruleResolver = context.getActionGraphBuilder();

    CxxPlatform.Builder cxxPlatform = CxxPlatform.builder();

    // TODO(cjhopman): Support these when we need them.
    cxxPlatform.setCuda(Optional.empty());
    cxxPlatform.setCudapp(Optional.empty());
    cxxPlatform.setHip(Optional.empty());
    cxxPlatform.setHippp(Optional.empty());
    cxxPlatform.setAsm(Optional.empty());

    cxxPlatform.setCudaflags(ImmutableList.of());
    cxxPlatform.setCudappflags(ImmutableList.of());
    cxxPlatform.setHipflags(ImmutableList.of());
    cxxPlatform.setHipppflags(ImmutableList.of());
    cxxPlatform.setAsmflags(ImmutableList.of());

    if (args.getUseHeaderMap()) {
      if (args.getPrivateHeadersSymlinksEnabled() || args.getPublicHeadersSymlinksEnabled()) {
        cxxPlatform.setHeaderMode(HeaderMode.SYMLINK_TREE_WITH_HEADER_MAP);
      } else {
        cxxPlatform.setHeaderMode(HeaderMode.HEADER_MAP_ONLY);
      }
    } else {
      cxxPlatform.setHeaderMode(HeaderMode.SYMLINK_TREE_ONLY);
    }

    // TODO(cjhopman): How to handle this?
    cxxPlatform.setFlagMacros(ImmutableMap.of());

    cxxPlatform.setConflictingHeaderBasenameWhitelist(ImmutableSortedSet.of());

    // Below here are all the things that we actually support configuration of. They mostly match
    // CxxPlatform, but (1) are in some cases more restrictive and (2) use more descriptive names.
    cxxPlatform.setPrivateHeadersSymlinksEnabled(args.getPrivateHeadersSymlinksEnabled());
    cxxPlatform.setPublicHeadersSymlinksEnabled(args.getPublicHeadersSymlinksEnabled());

    cxxPlatform.setUseArgFile(args.getUseArgFile());

    cxxPlatform.setSharedLibraryExtension(args.getSharedLibraryExtension());
    cxxPlatform.setSharedLibraryVersionedExtensionFormat(
        args.getSharedLibraryVersionedExtensionFormat());
    cxxPlatform.setStaticLibraryExtension(args.getStaticLibraryExtension());
    cxxPlatform.setObjectFileExtension(args.getObjectFileExtension());
    cxxPlatform.setBinaryExtension(args.getBinaryExtension());

    CxxToolProvider.Type compilerType = args.getCompilerType();
    LinkerProvider.Type linkerType = args.getLinkerType();
    // This should be the same for all the tools that use it.
    final boolean preferDependencyTree = false;

    cxxPlatform.setAs(
        new CompilerProvider(
            ToolProviders.getToolProvider(args.getAssembler()),
            compilerType,
            ToolType.AS,
            preferDependencyTree));
    cxxPlatform.setAsflags(args.getAssemblerFlags());

    cxxPlatform.setAspp(
        new PreprocessorProvider(
            ToolProviders.getToolProvider(args.getAssembler()), compilerType, ToolType.ASPP));
    cxxPlatform.setAsppflags(ImmutableList.of());

    cxxPlatform.setCc(
        new CompilerProvider(
            ToolProviders.getToolProvider(args.getCCompiler()),
            compilerType,
            ToolType.CC,
            preferDependencyTree));
    cxxPlatform.setCflags(args.getCCompilerFlags());

    cxxPlatform.setCxx(
        new CompilerProvider(
            ToolProviders.getToolProvider(args.getCxxCompiler()),
            compilerType,
            ToolType.CXX,
            preferDependencyTree));
    cxxPlatform.setCxxflags(args.getCxxCompilerFlags());

    cxxPlatform.setCpp(
        new PreprocessorProvider(
            ToolProviders.getToolProvider(args.getCCompiler()), compilerType, ToolType.CPP));
    cxxPlatform.setCppflags(ImmutableList.of());

    cxxPlatform.setCxxpp(
        new PreprocessorProvider(
            ToolProviders.getToolProvider(args.getCxxCompiler()), compilerType, ToolType.CXXPP));
    cxxPlatform.setCxxppflags(ImmutableList.of());

    cxxPlatform.setLd(
        new DefaultLinkerProvider(
            linkerType, ToolProviders.getToolProvider(args.getLinker()), true));

    if (linkerType == LinkerProvider.Type.GNU || linkerType == LinkerProvider.Type.DARWIN) {
      cxxPlatform.setLdflags(
          ImmutableList.<String>builder()
              // Add a deterministic build ID.
              .add("-Wl,--build-id")
              .addAll(args.getLinkerFlags())
              .build());
    } else {
      // TODO(cjhopman): We should force build ids by default for all linkers.
      cxxPlatform.setLdflags(args.getLinkerFlags());
    }

    cxxPlatform.setAr(
        ArchiverProvider.from(
            ToolProviders.getToolProvider(args.getArchiver()), args.getArchiverType()));
    cxxPlatform.setArflags(args.getArchiverFlags());

    cxxPlatform.setStrip(
        ToolProviders.getToolProvider(args.getStrip())
            .resolve(ruleResolver, buildTarget.getTargetConfiguration()));
    cxxPlatform.setStripFlags(args.getStripFlags());

    cxxPlatform.setRanlib(args.getRanlib().map(ToolProviders::getToolProvider));
    cxxPlatform.setRanlibflags(args.getRanlibFlags());

    ListMultimap<LinkableDepType, String> runtimeLdFlags =
        Multimaps.newListMultimap(new LinkedHashMap<>(), ArrayList::new);
    runtimeLdFlags.putAll(LinkableDepType.STATIC, args.getStaticDepRuntimeLdFlags());
    runtimeLdFlags.putAll(LinkableDepType.STATIC_PIC, args.getStaticPicDepRuntimeLdFlags());
    runtimeLdFlags.putAll(LinkableDepType.SHARED, args.getSharedDepRuntimeLdFlags());
    cxxPlatform.setRuntimeLdflags(runtimeLdFlags);

    cxxPlatform.setSymbolNameTool(
        new PosixNmSymbolNameTool(
            ToolProviders.getToolProvider(args.getNm())
                .resolve(ruleResolver, buildTarget.getTargetConfiguration())));

    // User-configured cxx platforms are required to handle path sanitization themselves.
    cxxPlatform.setCompilerDebugPathSanitizer(NoopDebugPathSanitizer.INSTANCE);

    // We require that untracked headers are errors and don't allow any whitelisting (the
    // user-configured platform can implement it's only filtering of the produced depfiles).
    cxxPlatform.setHeaderVerification(
        HeaderVerification.of(
            HeaderVerification.Mode.ERROR, ImmutableList.of(), ImmutableList.of()));

    SharedLibraryInterfaceParams.Type sharedLibraryInterfaceType =
        args.getSharedLibraryInterfaceType();
    // TODO(cjhopman): We should change this to force users to implement all of the shared library
    // interface logic.
    if (sharedLibraryInterfaceType == Type.DISABLED) {
      cxxPlatform.setSharedLibraryInterfaceParams(Optional.empty());
    } else {
      cxxPlatform.setSharedLibraryInterfaceParams(
          ElfSharedLibraryInterfaceParams.of(
              ToolProviders.getToolProvider(args.getObjcopyForSharedLibraryInterface()),
              ImmutableList.of(),
              sharedLibraryInterfaceType == Type.DEFINED_ONLY));
    }

    // TODO(cjhopman): Is this reasonable?
    cxxPlatform.setConflictingHeaderBasenameWhitelist(ImmutableSortedSet.of());

    cxxPlatform.setArchiveContents(ArchiveContents.NORMAL);

    cxxPlatform.setFilepathLengthLimited(args.getFilepathLengthLimited());

    return new CxxToolchainBuildRule(buildTarget, context, cxxPlatform);
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @Override
  public Class<CxxToolchainDescriptionArg> getConstructorArgType() {
    return CxxToolchainDescriptionArg.class;
  }

  /**
   * This is roughly analagous to the configuration provided by {@link CxxBuckConfig}. Some things
   * are not yet exposed/implemented, and others have been slightly renamed or exposed slightly
   * differently to be more restricted or more descriptive or more maintainable.
   */
  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractCxxToolchainDescriptionArg extends CommonDescriptionArg {
    /** When building or creating a project, create symlinks for the public headers if it's true. */
    @Value.Default
    default boolean getPrivateHeadersSymlinksEnabled() {
      return true;
    }

    /**
     * When building or creating a project, create symlinks for the public headers if it's true. It
     * would allow public headers to include an other public header with #include "foobar.h"\ even
     * if it's not in the same folder.
     */
    @Value.Default
    default boolean getPublicHeadersSymlinksEnabled() {
      return true;
    }

    /** Whether to use an argfile for long command lines. */
    @Value.Default
    default boolean getUseArgFile() {
      return true;
    }

    /** Extension of shared library files. */
    String getSharedLibraryExtension();

    /** Extension format for versioned shared libraries. */
    // TODO(agallagher): Improve documentation.
    String getSharedLibraryVersionedExtensionFormat();

    /** Extension for static library files. */
    String getStaticLibraryExtension();

    /** Extension for object files. */
    String getObjectFileExtension();

    /** Extension for binary files. */
    Optional<String> getBinaryExtension();

    /** {@link CxxToolProvider.Type} of the compiler. */
    CxxToolProvider.Type getCompilerType();

    /** {@link LinkerProvider.Type} of the linker. */
    LinkerProvider.Type getLinkerType();

    /** Assembler binary. */
    SourcePath getAssembler();

    /** Flags for the assembler. */
    ImmutableList<String> getAssemblerFlags();

    /** C compiler binary. */
    SourcePath getCCompiler();

    /** C compiler flags. */
    ImmutableList<String> getCCompilerFlags();

    /** C++ compiler binary. */
    SourcePath getCxxCompiler();

    /** C++ compiler flags. */
    ImmutableList<String> getCxxCompilerFlags();

    /** Linker binary. */
    SourcePath getLinker();

    /** Linker flags. */
    ImmutableList<String> getLinkerFlags();

    /** Archiver binary. */
    SourcePath getArchiver();

    /** Archiver flags. */
    ImmutableList<String> getArchiverFlags();

    /** {@link ArchiverProvider.Type} of the archiver. */
    ArchiverProvider.Type getArchiverType();

    /** Strip binary. */
    SourcePath getStrip();

    /** Ranlib binary. */
    Optional<SourcePath> getRanlib();

    /** Ranlib flags. */
    ImmutableList<String> getRanlibFlags();

    /** Strip flags. */
    ImmutableList<String> getStripFlags();

    /** Flags for linking the c/c++ runtime for static libraries. */
    ImmutableList<String> getStaticDepRuntimeLdFlags();

    /** Flags for linking the c/c++ runtime for static-pic libraries. */
    ImmutableList<String> getStaticPicDepRuntimeLdFlags();

    /** Flags for linking the c/c++ runtime for shared libraries. */
    ImmutableList<String> getSharedDepRuntimeLdFlags();

    /** nm binary. */
    SourcePath getNm();

    /** Type of shared library interfaces to create. */
    SharedLibraryInterfaceParams.Type getSharedLibraryInterfaceType();

    /** Objcopy binary to use for creating shared library interfaces. */
    SourcePath getObjcopyForSharedLibraryInterface();

    /** Whether to use header maps. */
    boolean getUseHeaderMap();

    /** Whether to use shorter intermediate files. */
    @Value.Default
    default boolean getFilepathLengthLimited() {
      return false;
    }
  }
}

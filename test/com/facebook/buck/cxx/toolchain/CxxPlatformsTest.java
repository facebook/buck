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

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig.ToolType;
import com.facebook.buck.cxx.toolchain.CxxToolProvider.Type;
import com.facebook.buck.cxx.toolchain.linker.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.CaseFormat;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.junit.ExpectedException;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link CxxPlatforms}. */
public class CxxPlatformsTest {
  @Rule public final ExpectedException expectedException = ExpectedException.none();

  @Test
  public void returnsKnownDefaultPlatformSetInConfig() {
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx", ImmutableMap.of("default_platform", "borland_cxx_452"));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    HashedFileTool borland =
        new HashedFileTool(PathSourcePath.of(filesystem, Paths.get("borland")));
    CxxPlatform borlandCxx452Platform =
        CxxPlatform.builder()
            .setFlavor(InternalFlavor.of("borland_cxx_452"))
            .setAs(createCompilerProvider(filesystem, ToolType.AS))
            .setAspp(createPreprocessorProvider(filesystem, ToolType.ASPP))
            .setCc(createCompilerProvider(filesystem, ToolType.CC))
            .setCpp(createPreprocessorProvider(filesystem, ToolType.CPP))
            .setCxx(createCompilerProvider(filesystem, ToolType.CXX))
            .setCxxpp(createPreprocessorProvider(filesystem, ToolType.CXXPP))
            .setLd(
                new DefaultLinkerProvider(
                    LinkerProvider.Type.GNU, new ConstantToolProvider(borland), true))
            .setStrip(borland)
            .setSymbolNameTool(new PosixNmSymbolNameTool(borland))
            .setAr(ArchiverProvider.from(new GnuArchiver(borland)))
            .setRanlib(new ConstantToolProvider(borland))
            .setSharedLibraryExtension("so")
            .setSharedLibraryVersionedExtensionFormat(".so.%s")
            .setStaticLibraryExtension("a")
            .setObjectFileExtension("so")
            .setCompilerDebugPathSanitizer(CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER)
            .setHeaderVerification(CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification())
            .setPublicHeadersSymlinksEnabled(true)
            .setPrivateHeadersSymlinksEnabled(true)
            .setArchiveContents(ArchiveContents.NORMAL)
            .build();

    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertThat(
        CxxPlatforms.getConfigDefaultCxxPlatform(
                new CxxBuckConfig(buckConfig),
                ImmutableMap.of(
                    borlandCxx452Platform.getFlavor(),
                    new StaticUnresolvedCxxPlatform(borlandCxx452Platform)),
                CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM)
            .resolve(new TestActionGraphBuilder()),
        equalTo(borlandCxx452Platform));
  }

  private PreprocessorProvider createPreprocessorProvider(
      ProjectFilesystem filesystem, ToolType toolType) {
    return new PreprocessorProvider(
        new ConstantToolProvider(
            new HashedFileTool(
                Suppliers.ofInstance(PathSourcePath.of(filesystem, Paths.get("borland"))))),
        Optional.of(Type.GCC)
            .orElseGet(
                () ->
                    CxxToolTypeInferer.getTypeFromPath(
                        Suppliers.ofInstance(PathSourcePath.of(filesystem, Paths.get("borland")))
                            .get())),
        toolType);
  }

  private CompilerProvider createCompilerProvider(ProjectFilesystem filesystem, ToolType toolType) {
    return new CompilerProvider(
        new ConstantToolProvider(
            new HashedFileTool(
                Suppliers.ofInstance(PathSourcePath.of(filesystem, Paths.get("borland"))))),
        () -> Optional.of(Type.GCC).get(),
        toolType,
        false);
  }

  @Test
  public void unknownDefaultPlatformSetInConfigFallsBackToSystemDefault() {
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx", ImmutableMap.of("default_platform", "borland_cxx_452"));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertThat(
        CxxPlatforms.getConfigDefaultCxxPlatform(
            new CxxBuckConfig(buckConfig),
            ImmutableMap.of(),
            CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM),
        equalTo(CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM));
  }

  public LinkerProvider getPlatformLinker(LinkerProvider.Type linkerType) {
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of(
            "cxx",
            ImmutableMap.of(
                "ld", Paths.get("fake_path").toString(),
                "linker_platform", linkerType.name()));

    CxxBuckConfig buckConfig =
        new CxxBuckConfig(
            FakeBuckConfig.builder()
                .setSections(sections)
                .setFilesystem(new FakeProjectFilesystem(ImmutableSet.of(Paths.get("fake_path"))))
                .build());

    return CxxPlatformUtils.build(buckConfig).getLd();
  }

  @Test
  public void linkerOverriddenByConfig() {
    assertThat(
        "MACOS linker was not a DarwinLinker instance",
        getPlatformLinker(LinkerProvider.Type.DARWIN).getType(),
        is(LinkerProvider.Type.DARWIN));
    assertThat(
        "LINUX linker was not a GnuLinker instance",
        getPlatformLinker(LinkerProvider.Type.GNU).getType(),
        is(LinkerProvider.Type.GNU));
    assertThat(
        "WINDOWS linker was not a GnuLinker instance",
        getPlatformLinker(LinkerProvider.Type.WINDOWS).getType(),
        is(LinkerProvider.Type.WINDOWS));
  }

  @Test
  public void invalidLinkerOverrideFails() {
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of(
            "cxx",
            ImmutableMap.of(
                "ld", Paths.get("fake_path").toString(), "linker_platform", "WRONG_PLATFORM"));

    CxxBuckConfig buckConfig =
        new CxxBuckConfig(
            FakeBuckConfig.builder()
                .setSections(sections)
                .setFilesystem(new FakeProjectFilesystem(ImmutableSet.of(Paths.get("fake_path"))))
                .build());

    expectedException.expect(RuntimeException.class);
    CxxPlatformUtils.build(buckConfig);
  }

  public Archiver getPlatformArchiver(Platform archiverPlatform) {
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of(
            "cxx",
            ImmutableMap.of(
                "ar", Paths.get("fake_path").toString(),
                "archiver_platform", archiverPlatform.name()));

    CxxBuckConfig buckConfig =
        new CxxBuckConfig(
            FakeBuckConfig.builder()
                .setSections(sections)
                .setFilesystem(new FakeProjectFilesystem(ImmutableSet.of(Paths.get("fake_path"))))
                .build());

    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    return CxxPlatformUtils.build(buckConfig)
        .getAr()
        .resolve(ruleResolver, EmptyTargetConfiguration.INSTANCE);
  }

  @Test
  public void archiverrOverriddenByConfig() {
    assertThat(
        "MACOS archiver was not a BsdArchiver instance",
        getPlatformArchiver(Platform.MACOS),
        instanceOf(BsdArchiver.class));
    assertThat(
        "LINUX archiver was not a GnuArchiver instance",
        getPlatformArchiver(Platform.LINUX),
        instanceOf(GnuArchiver.class));
    assertThat(
        "WINDOWS archiver was not a GnuArchiver instance",
        getPlatformArchiver(Platform.WINDOWS),
        instanceOf(WindowsArchiver.class));
  }

  @Test
  public void sharedLibraryExtensionOverride() {
    Flavor flavor = InternalFlavor.of("custom");
    String extension = "foo";
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx#" + flavor, ImmutableMap.of("shared_library_extension", extension));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertThat(
        CxxPlatforms.copyPlatformWithFlavorAndConfig(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Platform.UNKNOWN,
                new CxxBuckConfig(buckConfig, flavor),
                InternalFlavor.of("custom"))
            .getSharedLibraryExtension(),
        equalTo(extension));
  }

  @Test
  public void staticLibraryExtensionOverride() {
    Flavor flavor = InternalFlavor.of("custom");
    String extension = "foo";
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx#" + flavor, ImmutableMap.of("static_library_extension", extension));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertThat(
        CxxPlatforms.copyPlatformWithFlavorAndConfig(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Platform.UNKNOWN,
                new CxxBuckConfig(buckConfig, flavor),
                InternalFlavor.of("custom"))
            .getStaticLibraryExtension(),
        equalTo(extension));
  }

  @Test
  public void objectFileExtensionOverride() {
    Flavor flavor = InternalFlavor.of("custom");
    String extension = "bar";
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx#" + flavor, ImmutableMap.of("object_file_extension", extension));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertThat(
        CxxPlatforms.copyPlatformWithFlavorAndConfig(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Platform.UNKNOWN,
                new CxxBuckConfig(buckConfig, flavor),
                InternalFlavor.of("custom"))
            .getObjectFileExtension(),
        equalTo(extension));
  }

  @Test
  public void binaryExtensionOverride() {
    Flavor flavor = InternalFlavor.of("custom");
    String extension = "bar";
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx#" + flavor, ImmutableMap.of("binary_extension", extension));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertThat(
        CxxPlatforms.copyPlatformWithFlavorAndConfig(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Platform.UNKNOWN,
                new CxxBuckConfig(buckConfig, flavor),
                InternalFlavor.of("custom"))
            .getBinaryExtension(),
        equalTo(Optional.of(extension)));
  }

  @Test
  public void binaryExtensionEmptyOverride() {
    Flavor flavor = InternalFlavor.of("custom");
    String extension = "";
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx#" + flavor, ImmutableMap.of("binary_extension", extension));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertThat(
        CxxPlatforms.copyPlatformWithFlavorAndConfig(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Platform.UNKNOWN,
                new CxxBuckConfig(buckConfig, flavor),
                InternalFlavor.of("custom"))
            .getBinaryExtension(),
        equalTo(Optional.empty()));
  }

  @Test
  public void archiveContentsPlatformOverride() {
    Flavor flavor = InternalFlavor.of("custom");
    ArchiveContents archiveContents = ArchiveContents.THIN;
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of(
            "cxx#" + flavor,
            ImmutableMap.of(
                "archive_contents",
                CaseFormat.UPPER_UNDERSCORE.to(
                    CaseFormat.LOWER_UNDERSCORE, archiveContents.name())));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertEquals(
        CxxPlatforms.copyPlatformWithFlavorAndConfig(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Platform.UNKNOWN,
                new CxxBuckConfig(buckConfig, flavor),
                InternalFlavor.of("custom"))
            .getArchiveContents(),
        archiveContents);
  }
}

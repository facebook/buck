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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.toolchain.linker.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.SingleThreadedBuildRuleResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
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
    CompilerProvider compiler =
        new CompilerProvider(Paths.get("borland"), Optional.of(CxxToolProvider.Type.GCC));
    PreprocessorProvider preprocessor =
        new PreprocessorProvider(Paths.get("borland"), Optional.of(CxxToolProvider.Type.GCC));
    CxxPlatform borlandCxx452Platform =
        CxxPlatform.builder()
            .setFlavor(InternalFlavor.of("borland_cxx_452"))
            .setAs(compiler)
            .setAspp(preprocessor)
            .setCc(compiler)
            .setCpp(preprocessor)
            .setCxx(compiler)
            .setCxxpp(preprocessor)
            .setLd(
                new DefaultLinkerProvider(
                    LinkerProvider.Type.GNU,
                    new ConstantToolProvider(new HashedFileTool(Paths.get("borland")))))
            .setStrip(new HashedFileTool(Paths.get("borland")))
            .setSymbolNameTool(new PosixNmSymbolNameTool(new HashedFileTool(Paths.get("borland"))))
            .setAr(ArchiverProvider.from(new GnuArchiver(new HashedFileTool(Paths.get("borland")))))
            .setRanlib(new ConstantToolProvider(new HashedFileTool(Paths.get("borland"))))
            .setSharedLibraryExtension("so")
            .setSharedLibraryVersionedExtensionFormat(".so.%s")
            .setStaticLibraryExtension("a")
            .setObjectFileExtension("so")
            .setCompilerDebugPathSanitizer(CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER)
            .setAssemblerDebugPathSanitizer(CxxPlatformUtils.DEFAULT_ASSEMBLER_DEBUG_PATH_SANITIZER)
            .setHeaderVerification(CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification())
            .setPublicHeadersSymlinksEnabled(true)
            .setPrivateHeadersSymlinksEnabled(true)
            .build();

    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertThat(
        CxxPlatforms.getConfigDefaultCxxPlatform(
            new CxxBuckConfig(buckConfig),
            ImmutableMap.of(borlandCxx452Platform.getFlavor(), borlandCxx452Platform),
            CxxPlatformUtils.DEFAULT_PLATFORM),
        equalTo(borlandCxx452Platform));
  }

  @Test
  public void unknownDefaultPlatformSetInConfigFallsBackToSystemDefault() {
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx", ImmutableMap.of("default_platform", "borland_cxx_452"));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertThat(
        CxxPlatforms.getConfigDefaultCxxPlatform(
            new CxxBuckConfig(buckConfig), ImmutableMap.of(), CxxPlatformUtils.DEFAULT_PLATFORM),
        equalTo(CxxPlatformUtils.DEFAULT_PLATFORM));
  }

  @Test
  public void compilerOnlyFlagsNotAddedToPreprocessor() {
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of(
            "cxx",
            ImmutableMap.of(
                "compiler_only_flags", "-Wtest",
                "cppflags", "-Wp",
                "cxxppflags", "-Wxp"));

    CxxBuckConfig buckConfig =
        new CxxBuckConfig(FakeBuckConfig.builder().setSections(sections).build());

    CxxPlatform platform = CxxPlatformUtils.build(buckConfig);

    assertThat(platform.getCflags(), hasItem("-Wtest"));
    assertThat(platform.getCxxflags(), hasItem("-Wtest"));
    assertThat(platform.getCppflags(), hasItem("-Wp"));
    assertThat(platform.getCppflags(), not(hasItem("-Wtest")));
    assertThat(platform.getCxxppflags(), hasItem("-Wxp"));
    assertThat(platform.getCxxppflags(), not(hasItem("-Wtest")));
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

    BuildRuleResolver ruleResolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    return CxxPlatformUtils.build(buckConfig).getAr().resolve(ruleResolver);
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
}

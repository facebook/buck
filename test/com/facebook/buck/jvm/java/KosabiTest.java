/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import static com.facebook.buck.android.RobolectricTestBuilder.DEFAULT_ANDROID_COMPILER_FACTORY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidLibraryDescriptionArg;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.toolchain.JavaToolchain;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.jvm.kotlin.Kosabi;
import com.facebook.buck.jvm.kotlin.KosabiConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class KosabiTest {
  private final String KOSABI_JVM_ABI_GEN_PLUGIN_NAME = "JVM_ABI";
  private final String KOSABI_STUBS_GEN_PLUGIN_NAME = "STUBS_GEN";
  private final String KOSABI_JVM_ABI_GEN_PLUGIN_TARGET = "//:" + KOSABI_JVM_ABI_GEN_PLUGIN_NAME;
  private final String KOSABI_STUBS_GEN_PLUGIN_TARGET = "//:" + KOSABI_STUBS_GEN_PLUGIN_NAME;

  private BuildTarget jvmABITarget;
  private BuildTarget stubGenTarget;

  private ProjectFilesystem projectFilesystem;
  private CellPathResolver cellPathResolver;
  private BuckConfig buckConfig;
  private KosabiConfig kosabiConfig;
  JavacOptions javacOptions;
  private ToolchainProvider toolchainProvider;
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Before
  public void setUp() {
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toPath());
    cellPathResolver = TestCellPathResolver.get(projectFilesystem);
    buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    KosabiConfig.SECTION,
                    ImmutableMap.of(
                        // Important to set the plugin target in the config
                        KosabiConfig.PROPERTY_KOSABI_STUBS_GEN_PLUGIN,
                        KOSABI_STUBS_GEN_PLUGIN_TARGET,
                        KosabiConfig.PROPERTY_KOSABI_JVM_ABI_GEN_PLUGIN,
                        KOSABI_JVM_ABI_GEN_PLUGIN_TARGET)))
            .build();
    jvmABITarget = BuildTargetFactory.newInstance(KOSABI_JVM_ABI_GEN_PLUGIN_TARGET);
    stubGenTarget = BuildTargetFactory.newInstance(KOSABI_STUBS_GEN_PLUGIN_TARGET);
    kosabiConfig = KosabiConfig.of(buckConfig);

    // Now create toolchain providers for javac options and javac tool
    // We need both to successfully build a JavaLibrary rule
    javacOptions = JavacOptions.builder().build();
    Toolchain javacOptionsProvider = JavacOptionsProvider.of(javacOptions);
    Toolchain javacToolchain =
        JavaToolchain.of(new ConstantJavacProvider(new ExternalJavac(FakeTool::new, "fakeJavac")));
    toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(JavacOptionsProvider.DEFAULT_NAME, javacOptionsProvider)
            .withToolchain(JavaToolchain.DEFAULT_NAME, javacToolchain)
            .build();
  }

  @Test
  public void testPluginOptionsMappings() {
    // Create a register a mock Android library
    BuildTarget library = BuildTargetFactory.newInstance("//:lib");
    AndroidLibraryBuilder.createBuilder(library, JavaBuckConfig.of(buckConfig))
        .build(projectFilesystem);

    ImmutableMap<String, SourcePath> pluginOptionsMappings =
        Kosabi.getPluginOptionsMappings(library.getTargetConfiguration(), kosabiConfig);

    assertEquals(
        KOSABI_JVM_ABI_GEN_PLUGIN_TARGET,
        pluginOptionsMappings.get(KosabiConfig.PROPERTY_KOSABI_JVM_ABI_GEN_PLUGIN).toString());
    assertEquals(
        KOSABI_STUBS_GEN_PLUGIN_TARGET,
        pluginOptionsMappings.get(KosabiConfig.PROPERTY_KOSABI_STUBS_GEN_PLUGIN).toString());
  }

  @Test
  public void testPluginOptionsMappingsWithOnePluginSetup() {
    // only add setup one plugin
    buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    KosabiConfig.SECTION,
                    ImmutableMap.of(
                        KosabiConfig.PROPERTY_KOSABI_JVM_ABI_GEN_PLUGIN,
                        KOSABI_JVM_ABI_GEN_PLUGIN_TARGET)))
            .build();
    kosabiConfig = KosabiConfig.of(buckConfig);

    // Create a register a mock Android library
    BuildTarget library = BuildTargetFactory.newInstance("//:lib");
    AndroidLibraryBuilder.createBuilder(library, JavaBuckConfig.of(buckConfig))
        .build(projectFilesystem);

    ImmutableMap<String, SourcePath> pluginOptionsMappings =
        Kosabi.getPluginOptionsMappings(library.getTargetConfiguration(), kosabiConfig);

    assertTrue(pluginOptionsMappings.isEmpty());
  }

  @Test
  public void testFlavorIntegration() {
    // Create a register a mock Android library
    BuildTarget library = BuildTargetFactory.newInstance("//:androidLib");
    AndroidLibraryBuilder.createBuilder(library, JavaBuckConfig.of(buckConfig))
        .build(projectFilesystem);

    // Now we add flavor to the mock library and check that AndroidLibraryDescription
    // properly handles the flavor
    BuildTarget flavored = library.withFlavors(JavaAbis.SOURCE_ONLY_ABI_FLAVOR);
    AndroidLibraryDescription androidLibraryDescription =
        new AndroidLibraryDescription(
            JavaBuckConfig.of(buckConfig),
            JavaCDBuckConfig.of(buckConfig),
            DownwardApiConfig.of(buckConfig),
            DEFAULT_ANDROID_COMPILER_FACTORY,
            toolchainProvider);

    // Check: Kosabi plugins should be added as a parse time dep
    ImmutableList.Builder<BuildTarget> parseTimeDepsBuilder = ImmutableList.builder();
    AndroidLibraryDescriptionArg arg =
        AndroidLibraryDescriptionArg.builder().setName("plugins").build();
    androidLibraryDescription.findDepsForTargetFromConstructorArgs(
        flavored,
        cellPathResolver.getCellNameResolver(),
        arg,
        ImmutableList.builder(),
        parseTimeDepsBuilder);
    assertTrue(parseTimeDepsBuilder.build().stream().anyMatch(jvmABITarget::equals));
    assertTrue(parseTimeDepsBuilder.build().stream().anyMatch(stubGenTarget::equals));
  }
}

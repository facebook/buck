/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.nullsafe.NullsafeConfig;
import com.facebook.buck.jvm.java.toolchain.JavaToolchain;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NullsafeTest {
  private final String NULLSAFE_PLUGIN_NAME = "Nullsafe";
  private final String NULLSAFE_TARGET_NAME = "nullsafe_plugin";
  private final String NULLSAFE_TARGET = "//:" + NULLSAFE_TARGET_NAME;
  private final String NULLSAFE_EXTRA_ARG = "-Anullsafe.extraArg=true";

  private BuildTarget nullsafeTarget;

  private ActionGraphBuilder graphBuilder;
  private ProjectFilesystem projectFilesystem;
  private CellPathResolver cellPathResolver;
  private BuckConfig buckConfig;
  private NullsafeConfig nullsafeConfig;
  JavacOptions javacOptions;
  private ToolchainProvider toolchainProvider;
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Before
  public void setUp() {
    // We need quite a bit of setup here
    graphBuilder = new TestActionGraphBuilder();
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toPath());
    cellPathResolver = TestCellPathResolver.get(projectFilesystem);
    buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    NullsafeConfig.SECTION,
                    ImmutableMap.of(
                        // Important to set the plugin target in the config
                        NullsafeConfig.PLUGIN_FIELD,
                        NULLSAFE_TARGET,
                        NullsafeConfig.EXTRA_ARGS_FIELD,
                        NULLSAFE_EXTRA_ARG)))
            .build();
    nullsafeTarget = BuildTargetFactory.newInstance(NULLSAFE_TARGET);
    nullsafeConfig = NullsafeConfig.of(buckConfig);

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

    // Pre-register nullsafe plugin target in the build graph
    // Without this the build won't be able to find a rule for
    // nullsafe plugin
    graphBuilder.computeIfAbsent(nullsafeTarget, target -> this.createNullsafePlugin());
  }

  // Helper to create a fake Nullsafe plugin
  private BuildRule createNullsafePlugin() {
    JavaPluginDescriptionArg arg =
        JavaPluginDescriptionArg.builder()
            .setName(NULLSAFE_TARGET_NAME)
            .setPluginName(NULLSAFE_PLUGIN_NAME)
            .build();

    BuildTarget nullsafeTarget = BuildTargetFactory.newInstance(NULLSAFE_TARGET);
    BuildRuleParams params =
        TestBuildRuleParams.create().withDeclaredDeps(graphBuilder.getAllRules(arg.getDeps()));

    return new JavaPluginDescription()
        .createBuildRule(
            TestBuildRuleCreationContextFactory.create(
                graphBuilder, projectFilesystem, toolchainProvider),
            nullsafeTarget,
            params,
            arg);
  }

  @Test
  public void testAugmentJavacOptions() {
    // Create a register a mock java library
    BuildTarget library = BuildTargetFactory.newInstance("//:lib");
    JavaLibraryBuilder.createBuilder(library, JavaBuckConfig.of(buckConfig), projectFilesystem)
        .build(graphBuilder);

    JavacOptions augmentedOpts =
        Nullsafe.augmentJavacOptions(
            javacOptions, library, graphBuilder, projectFilesystem, nullsafeConfig);

    // Augmented options should contain setup for nullsafe plugin
    assertTrue(
        augmentedOpts.getExtraArguments().stream().anyMatch(s -> s.contains("-XDcompilePolicy")));
    // Augmented options should contain extra arguments from config
    assertTrue(
        augmentedOpts.getExtraArguments().stream().anyMatch(s -> s.contains(NULLSAFE_EXTRA_ARG)));

    ResolvedJavacPluginProperties nullsafeProps =
        augmentedOpts.getStandardJavacPluginParams().getPluginProperties().get(0);
    assertTrue(
        nullsafeProps.getProcessorNames().stream().anyMatch(s -> s.equals(NULLSAFE_PLUGIN_NAME)));
  }

  @Test
  public void testFlavorIntegration() {
    // Create a register a mock java library
    BuildTarget library = BuildTargetFactory.newInstance("//:lib");
    JavaLibraryBuilder.createBuilder(library, JavaBuckConfig.of(buckConfig), projectFilesystem)
        .build(graphBuilder);

    // Now we add flavor to the mock library and check that JavaLibraryDescription
    // properly handles the flavor
    BuildTarget flavored = library.withFlavors(Nullsafe.NULLSAFEX);
    JavaLibraryDescriptionArg arg = JavaLibraryDescriptionArg.builder().setName("lib").build();
    BuildRuleParams params =
        TestBuildRuleParams.create().withDeclaredDeps(graphBuilder.getAllRules(arg.getDeps()));
    JavaLibraryDescription javaLibraryDescription =
        new JavaLibraryDescription(
            toolchainProvider,
            JavaBuckConfig.of(buckConfig),
            JavaCDBuckConfig.of(buckConfig),
            DownwardApiConfig.of(buckConfig));

    // Check: nullsafe plugin should be added as a parse time dep
    ImmutableList.Builder<BuildTarget> parseTimeDepsBuilder = ImmutableList.builder();
    javaLibraryDescription.findDepsForTargetFromConstructorArgs(
        flavored,
        cellPathResolver.getCellNameResolver(),
        arg,
        ImmutableList.builder(),
        parseTimeDepsBuilder);
    assertTrue(parseTimeDepsBuilder.build().stream().anyMatch(nullsafeTarget::equals));

    // Check: createBuildRule should return Nullsafe build rule rather than JavaLibrary rule
    BuildRule flavoredRule =
        javaLibraryDescription.createBuildRule(
            TestBuildRuleCreationContextFactory.create(
                graphBuilder, projectFilesystem, toolchainProvider),
            flavored,
            params,
            arg);
    assertTrue(flavoredRule instanceof Nullsafe);

    // Check: the rule that is actually in the build graph is Nullsafe rule not JavaLibrary rule
    // DefaultJavaLibraryRules does graph manipulation as a side-effect of building JavaLibrary
    // (i.e. it builds the library, but also proactively inserts it into the graph; and we don't
    // want that).
    graphBuilder.computeIfAbsent(flavored, target -> flavoredRule);
    BuildRule ruleInGraph = graphBuilder.requireRule(flavored);
    assertTrue(ruleInGraph instanceof Nullsafe);

    // Check: output of #nullsafex-json is a directory
    BuildTarget jsonFlavored = library.withFlavors(Nullsafe.NULLSAFEX_JSON);
    // Check: createBuildRule should return Nullsafe build rule rather than JavaLibrary rule
    BuildRule jsonFlavoredRule =
        javaLibraryDescription.createBuildRule(
            TestBuildRuleCreationContextFactory.create(
                graphBuilder, projectFilesystem, toolchainProvider),
            jsonFlavored,
            params,
            arg);
    graphBuilder.computeIfAbsent(jsonFlavored, target -> jsonFlavoredRule);

    SourcePath outputPath = jsonFlavoredRule.getSourcePathToOutput();
    assertTrue(
        graphBuilder
            .getSourcePathResolver()
            .getAbsolutePath(outputPath)
            .endsWith(Nullsafe.REPORTS_JSON_DIR));
  }
}

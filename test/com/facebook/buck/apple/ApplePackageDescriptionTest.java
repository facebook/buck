/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TestBuildRuleCreationContextFactory;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.sandbox.NoSandboxExecutionStrategy;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Test;

public class ApplePackageDescriptionTest {

  @Test
  public void descriptionCreatesExternallyBuiltPackageRuleIfConfigExists() {
    ApplePackageDescription description = descriptionWithCommand("echo");
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance("//foo:binary");
    BuildTarget bundleBuildTarget = BuildTargetFactory.newInstance("//foo:bundle");
    TargetGraph graph =
        TargetGraphFactory.newInstance(
            AppleBinaryBuilder.createBuilder(binaryBuildTarget).build(),
            AppleBundleBuilder.createBuilder(bundleBuildTarget)
                .setBinary(binaryBuildTarget)
                .setExtension(Either.ofLeft(AppleBundleExtension.APP))
                .setInfoPlist(FakeSourcePath.of("Info.plist"))
                .build());

    ApplePackageDescriptionArg arg =
        ApplePackageDescriptionArg.builder()
            .setName("package")
            .setBundle(bundleBuildTarget)
            .build();

    BuildTarget packageBuildTarget = BuildTargetFactory.newInstance("//foo:package#macosx-x86_64");

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(graph);

    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ImmutableSortedSet.Builder<BuildTarget> implicitDeps = ImmutableSortedSet.naturalOrder();
    description.findDepsForTargetFromConstructorArgs(
        packageBuildTarget,
        TestCellPathResolver.get(projectFilesystem),
        arg,
        implicitDeps,
        ImmutableSortedSet.naturalOrder());
    graphBuilder.requireAllRules(implicitDeps.build());
    BuildRule rule =
        description.createBuildRule(
            TestBuildRuleCreationContextFactory.create(graph, graphBuilder, projectFilesystem),
            packageBuildTarget,
            TestBuildRuleParams.create(),
            arg);

    assertThat(rule, instanceOf(ExternallyBuiltApplePackage.class));
    assertThat(
        rule.getBuildDeps(),
        hasItem(
            graphBuilder.getRule(
                bundleBuildTarget.withFlavors(InternalFlavor.of("macosx-x86_64")))));
  }

  @Test
  public void descriptionExpandsLocationMacrosAndTracksDependencies() {
    ApplePackageDescription description = descriptionWithCommand("echo $(location :exportfile)");
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance("//foo:binary");
    BuildTarget bundleBuildTarget = BuildTargetFactory.newInstance("//foo:bundle");
    BuildTarget exportFileBuildTarget = BuildTargetFactory.newInstance("//foo:exportfile");
    TargetGraph graph =
        TargetGraphFactory.newInstance(
            new ExportFileBuilder(exportFileBuildTarget).build(),
            AppleBinaryBuilder.createBuilder(binaryBuildTarget).build(),
            AppleBundleBuilder.createBuilder(bundleBuildTarget)
                .setBinary(binaryBuildTarget)
                .setExtension(Either.ofLeft(AppleBundleExtension.APP))
                .setInfoPlist(FakeSourcePath.of("Info.plist"))
                .build());

    ApplePackageDescriptionArg arg =
        ApplePackageDescriptionArg.builder()
            .setName("package")
            .setBundle(bundleBuildTarget)
            .build();

    BuildTarget packageBuildTarget = BuildTargetFactory.newInstance("//foo:package#macosx-x86_64");

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(graph);

    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();
    ImmutableSortedSet.Builder<BuildTarget> implicitDeps = ImmutableSortedSet.naturalOrder();
    description.findDepsForTargetFromConstructorArgs(
        packageBuildTarget,
        TestCellPathResolver.get(projectFilesystem),
        arg,
        implicitDeps,
        ImmutableSortedSet.naturalOrder());
    graphBuilder.requireAllRules(implicitDeps.build());
    BuildRule rule =
        description.createBuildRule(
            TestBuildRuleCreationContextFactory.create(graph, graphBuilder, projectFilesystem),
            packageBuildTarget,
            params,
            arg);

    assertThat(rule.getBuildDeps(), hasItem(graphBuilder.getRule(exportFileBuildTarget)));
  }

  private ApplePackageDescription descriptionWithCommand(String command) {
    return new ApplePackageDescription(
        new ToolchainProviderBuilder()
            .withToolchain(
                AppleCxxPlatformsProvider.DEFAULT_NAME,
                AppleCxxPlatformsProvider.of(
                    FakeAppleRuleDescriptions.DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN))
            .build(),
        new NoSandboxExecutionStrategy(),
        FakeBuckConfig.builder()
            .setSections(
                "[apple]",
                "macosx_package_command = " + command.replace("$", "\\$"),
                "macosx_package_extension = api")
            .build()
            .getView(AppleConfig.class));
  }
}

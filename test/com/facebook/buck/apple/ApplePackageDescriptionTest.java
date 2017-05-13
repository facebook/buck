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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeCellPathResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Test;

public class ApplePackageDescriptionTest {

  @Test
  public void descriptionCreatesExternallyBuiltPackageRuleIfConfigExists() throws Exception {
    ApplePackageDescription description = descriptionWithCommand("echo");
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance("//foo:binary");
    BuildTarget bundleBuildTarget = BuildTargetFactory.newInstance("//foo:bundle");
    TargetGraph graph =
        TargetGraphFactory.newInstance(
            AppleBinaryBuilder.createBuilder(binaryBuildTarget).build(),
            AppleBundleBuilder.createBuilder(bundleBuildTarget)
                .setBinary(binaryBuildTarget)
                .setExtension(Either.ofLeft(AppleBundleExtension.APP))
                .setInfoPlist(new FakeSourcePath("Info.plist"))
                .build());

    ApplePackageDescriptionArg arg =
        ApplePackageDescriptionArg.builder()
            .setName("package")
            .setBundle(bundleBuildTarget)
            .build();

    BuildTarget packageBuildTarget = BuildTargetFactory.newInstance("//foo:package#macosx-x86_64");

    BuildRuleResolver resolver =
        new BuildRuleResolver(graph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRuleParams params = new FakeBuildRuleParamsBuilder(packageBuildTarget).build();
    ImmutableSortedSet.Builder<BuildTarget> implicitDeps = ImmutableSortedSet.naturalOrder();
    description.findDepsForTargetFromConstructorArgs(
        packageBuildTarget,
        new FakeCellPathResolver(params.getProjectFilesystem()),
        arg,
        implicitDeps,
        ImmutableSortedSet.naturalOrder());
    resolver.requireAllRules(implicitDeps.build());
    BuildRule rule =
        description.createBuildRule(
            graph,
            new FakeBuildRuleParamsBuilder(packageBuildTarget).build(),
            resolver,
            TestCellBuilder.createCellRoots(params.getProjectFilesystem()),
            arg);

    assertThat(rule, instanceOf(ExternallyBuiltApplePackage.class));
    assertThat(
        rule.getBuildDeps(),
        hasItem(
            resolver.getRule(bundleBuildTarget.withFlavors(InternalFlavor.of("macosx-x86_64")))));
  }

  @Test
  public void descriptionExpandsLocationMacrosAndTracksDependencies() throws Exception {
    ApplePackageDescription description = descriptionWithCommand("echo $(location :exportfile)");
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance("//foo:binary");
    BuildTarget bundleBuildTarget = BuildTargetFactory.newInstance("//foo:bundle");
    BuildTarget exportFileBuildTarget = BuildTargetFactory.newInstance("//foo:exportfile");
    TargetGraph graph =
        TargetGraphFactory.newInstance(
            ExportFileBuilder.newExportFileBuilder(exportFileBuildTarget).build(),
            AppleBinaryBuilder.createBuilder(binaryBuildTarget).build(),
            AppleBundleBuilder.createBuilder(bundleBuildTarget)
                .setBinary(binaryBuildTarget)
                .setExtension(Either.ofLeft(AppleBundleExtension.APP))
                .setInfoPlist(new FakeSourcePath("Info.plist"))
                .build());

    ApplePackageDescriptionArg arg =
        ApplePackageDescriptionArg.builder()
            .setName("package")
            .setBundle(bundleBuildTarget)
            .build();

    BuildTarget packageBuildTarget = BuildTargetFactory.newInstance("//foo:package#macosx-x86_64");

    BuildRuleResolver resolver =
        new BuildRuleResolver(graph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRuleParams params = new FakeBuildRuleParamsBuilder(packageBuildTarget).build();
    ImmutableSortedSet.Builder<BuildTarget> implicitDeps = ImmutableSortedSet.naturalOrder();
    description.findDepsForTargetFromConstructorArgs(
        packageBuildTarget,
        new FakeCellPathResolver(params.getProjectFilesystem()),
        arg,
        implicitDeps,
        ImmutableSortedSet.naturalOrder());
    resolver.requireAllRules(implicitDeps.build());
    BuildRule rule =
        description.createBuildRule(
            graph,
            params,
            resolver,
            TestCellBuilder.createCellRoots(params.getProjectFilesystem()),
            arg);

    assertThat(rule.getBuildDeps(), hasItem(resolver.getRule(exportFileBuildTarget)));
  }

  private ApplePackageDescription descriptionWithCommand(String command) {
    return new ApplePackageDescription(
        FakeBuckConfig.builder()
            .setSections(
                "[apple]",
                "macosx_package_command = " + command.replace("$", "\\$"),
                "macosx_package_extension = api")
            .build()
            .getView(AppleConfig.class),
        CxxPlatformUtils.DEFAULT_PLATFORM,
        FakeAppleRuleDescriptions.DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN);
  }
}

/*
 * Copyright 2016-present Facebook, Inc.
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AppleLibraryDescriptionTest {

  @Test
  public void linkerFlagsLocationMacro() {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    BuildTarget sandboxTarget =
        BuildTargetFactory.newInstance("//:rule")
            .withFlavors(CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR, DefaultCxxPlatforms.FLAVOR);
    BuildRuleResolver resolver =
        new TestBuildRuleResolver(
            TargetGraphFactory.newInstance(new AppleLibraryBuilder(sandboxTarget).build()));
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    BuildTarget target =
        BuildTargetFactory.newInstance("//:rule")
            .withFlavors(DefaultCxxPlatforms.FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR);
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    AppleLibraryBuilder builder =
        new AppleLibraryBuilder(target)
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))));
    assertThat(builder.build().getExtraDeps(), Matchers.hasItem(dep.getBuildTarget()));
    BuildRule binary = builder.build(resolver);
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs(), pathResolver),
        Matchers.hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(binary.getBuildDeps(), Matchers.hasItem(dep));
  }

  @Test
  public void swiftMetadata() {
    SourcePath objCSourcePath = FakeSourcePath.of("foo.m");
    SourcePath swiftSourcePath = FakeSourcePath.of("bar.swift");

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:library");
    TargetNode<?, ?> binaryNode =
        new AppleLibraryBuilder(binaryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(objCSourcePath), SourceWithFlags.of(swiftSourcePath)))
            .build();

    BuildRuleResolver buildRuleResolver =
        new TestBuildRuleResolver(TargetGraphFactory.newInstance(binaryNode));

    BuildTarget swiftMetadataTarget =
        binaryTarget.withFlavors(
            AppleLibraryDescription.MetadataType.APPLE_SWIFT_METADATA.getFlavor());
    Optional<AppleLibrarySwiftMetadata> metadata =
        buildRuleResolver.requireMetadata(swiftMetadataTarget, AppleLibrarySwiftMetadata.class);
    assertTrue(metadata.isPresent());

    assertEquals(metadata.get().getNonSwiftSources().size(), 1);
    SourcePath expectedObjCSourcePath =
        metadata.get().getNonSwiftSources().iterator().next().getSourcePath();
    assertSame(objCSourcePath, expectedObjCSourcePath);

    assertEquals(metadata.get().getSwiftSources().size(), 1);
    SourcePath expectedSwiftSourcePath =
        metadata.get().getSwiftSources().iterator().next().getSourcePath();
    assertSame(swiftSourcePath, expectedSwiftSourcePath);
  }
}

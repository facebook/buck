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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntime;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleResolver;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.junit.Test;

public class StripLinkableTest {

  @Test
  public void testThatDepsIncludesTheThingsThatDepsShouldInclude() {
    // TODO(cjhopman): This is dumb. We should be able to depend on the framework doing the right
    // thing.
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:lib");
    BuildTarget toolTarget = BuildTargetFactory.newInstance("//:tool");

    FakeBuildRule libraryRule = new FakeBuildRule(libraryTarget);
    FakeBuildRule toolRule = new FakeBuildRule(toolTarget);

    ImmutableMap<BuildTarget, BuildRule> ruleMap =
        ImmutableMap.of(libraryTarget, libraryRule, toolTarget, toolRule);

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder =
        new AbstractBuildRuleResolver() {
          @Override
          public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
            return Optional.ofNullable(ruleMap.get(buildTarget));
          }
        };

    SourcePath libraryPath = DefaultBuildTargetSourcePath.of(libraryTarget);
    Tool stripTool = new HashedFileTool(DefaultBuildTargetSourcePath.of(toolTarget));

    NdkCxxPlatform platform =
        NdkCxxPlatform.builder()
            .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
            .setCxxRuntime(NdkCxxRuntime.GNUSTL)
            .setObjdump(new CommandTool.Builder().addArg("objdump").build())
            .build();

    StripLinkable stripRule =
        new StripLinkable(
            platform, target, filesystem, ruleFinder, stripTool, libraryPath, "somename.so", false);

    assertEquals(stripRule.getBuildDeps(), ImmutableSortedSet.of(libraryRule, toolRule));
  }

  @Test
  public void testStripFlags() {
    NdkCxxPlatform platformDefaultFlags =
        NdkCxxPlatform.builder()
            .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
            .setCxxRuntime(NdkCxxRuntime.GNUSTL)
            .setObjdump(new CommandTool.Builder().addArg("objdump").build())
            .build();

    NdkCxxPlatform platformCustomFlags =
        NdkCxxPlatform.builder()
            .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
            .setCxxRuntime(NdkCxxRuntime.GNUSTL)
            .setObjdump(new CommandTool.Builder().addArg("objdump").build())
            .setStripApkLibsFlags(ImmutableList.of(StringArg.of("-s")))
            .build();

    assertEquals(
        ImmutableList.of(StringArg.of("--strip-unneeded")),
        platformDefaultFlags.getStripApkLibsFlags());
    assertEquals(ImmutableList.of(StringArg.of("-s")), platformCustomFlags.getStripApkLibsFlags());
  }
}

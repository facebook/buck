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

package com.facebook.buck.haskell;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;

public class HaskellLibraryDescriptionTest {

  @Test
  public void compilerFlags() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    String flag = "-compiler-flag";
    HaskellLibraryBuilder builder =
        new HaskellLibraryBuilder(target)
            .setCompilerFlags(ImmutableList.of(flag));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    HaskellLibrary library = (HaskellLibrary) builder.build(resolver);
    library.getCompileInput(
        CxxPlatformUtils.DEFAULT_PLATFORM,
        Linker.LinkableDepType.STATIC);
    BuildTarget compileTarget =
        HaskellDescriptionUtils.getCompileBuildTarget(
            target,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC);
    HaskellCompileRule rule = resolver.getRuleWithType(compileTarget, HaskellCompileRule.class);
    assertThat(rule.getFlags(), Matchers.hasItem(flag));
  }

  @Test
  public void targetsAndOutputsAreDifferentBetweenLinkStyles() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(),
            new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget baseTarget = BuildTargetFactory.newInstance("//:rule");

    BuildRule staticLib =
        new HaskellLibraryBuilder(
            baseTarget.withFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                HaskellLibraryDescription.Type.STATIC.getFlavor()))
            .build(resolver);
    BuildRule staticPicLib =
        new HaskellLibraryBuilder(
            baseTarget.withFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                HaskellLibraryDescription.Type.STATIC_PIC.getFlavor()))
            .build(resolver);
    BuildRule sharedLib =
        new HaskellLibraryBuilder(
            baseTarget.withFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                HaskellLibraryDescription.Type.SHARED.getFlavor()))
            .build(resolver);

    ImmutableList<Path> outputs =
        ImmutableList.of(
            Preconditions.checkNotNull(staticLib.getPathToOutput()),
            Preconditions.checkNotNull(staticPicLib.getPathToOutput()),
            Preconditions.checkNotNull(sharedLib.getPathToOutput()));
    assertThat(outputs.size(), Matchers.equalTo(ImmutableSet.copyOf(outputs).size()));

    ImmutableList<BuildTarget> targets =
        ImmutableList.of(
            staticLib.getBuildTarget(),
            staticPicLib.getBuildTarget(),
            sharedLib.getBuildTarget());
    assertThat(targets.size(), Matchers.equalTo(ImmutableSet.copyOf(targets).size()));
  }

}

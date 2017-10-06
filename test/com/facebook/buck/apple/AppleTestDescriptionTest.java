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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SingleThreadedBuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AppleTestDescriptionTest {

  @Test
  public void linkerFlagsLocationMacro() throws Exception {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    GenruleBuilder depBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep")).setOut("out");
    AppleTestBuilder builder =
        new AppleTestBuilder(BuildTargetFactory.newInstance("//:rule#macosx-x86_64"))
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "--linker-script=%s", LocationMacro.of(depBuilder.getTarget()))))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))))
            .setInfoPlist(FakeSourcePath.of("Info.plist"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build(), depBuilder.build());
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    Genrule dep = depBuilder.build(resolver, targetGraph);
    assertThat(builder.build().getExtraDeps(), Matchers.hasItem(dep.getBuildTarget()));
    AppleTest test = builder.build(resolver, targetGraph);
    CxxStrip strip =
        (CxxStrip)
            RichStream.from(test.getBuildDeps())
                .filter(AppleBundle.class)
                .findFirst()
                .get()
                .getBinary()
                .get();
    BuildRule binary = strip.getBuildDeps().first();
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs(), pathResolver),
        Matchers.hasItem(
            String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath(pathResolver))));
    assertThat(binary.getBuildDeps(), Matchers.hasItem(dep));
  }

  @Test
  public void uiTestHasNoTestHost() throws Exception {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    BuildTarget testHostBinTarget = BuildTargetFactory.newInstance("//:testhostbin#macosx-x86_64");
    BuildTarget testHostBundleTarget =
        BuildTargetFactory.newInstance("//:testhostbundle#macosx-x86_64");
    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test#macosx-x86_64");

    AppleBinaryBuilder testHostBinaryBuilder =
        AppleBinaryBuilder.createBuilder(testHostBinTarget)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))));

    AppleBundleBuilder testHostBundleBuilder =
        AppleBundleBuilder.createBuilder(testHostBundleTarget)
            .setBinary(testHostBinTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(FakeSourcePath.of(("Info.plist")));

    AppleTestBuilder testBuilder =
        AppleTestBuilder.createBuilder(testTarget)
            .setInfoPlist(FakeSourcePath.of(("Info.plist")))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))))
            .isUiTest(true)
            .setTestHostApp(Optional.of(testHostBundleTarget));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            testBuilder.build(), testHostBundleBuilder.build(), testHostBinaryBuilder.build());
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    resolver.requireRule(testHostBundleTarget);
    AppleTest test = testBuilder.build(resolver, targetGraph);

    assertTrue(test.isUiTest());
    assertFalse(test.hasTestHost());
  }
}

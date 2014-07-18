/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

public class UberRDotJavaUtilTest {

  @Test
  public void testAndroidResourceCanDependOnResourcesOfAndroidPrebuiltAar() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    RuleKeyBuilderFactory ruleKeyBuilderFactory = new FakeRuleKeyBuilderFactory();

    BuildRuleParams aarParams = new BuildRuleParams(
        BuildTargetFactory.newInstance("//third-party:play-services"),
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of(),
        BuildTargetPattern.PUBLIC,
        projectFilesystem,
        ruleKeyBuilderFactory,
        AndroidPrebuiltAarDescription.TYPE);
    AndroidPrebuiltAarDescription.Arg aarArg = new AndroidPrebuiltAarDescription.Arg();
    aarArg.aar = new PathSourcePath(Paths.get("third-party/play-services-3.1.36.aar"));
    aarArg.deps = Optional.<ImmutableSortedSet<BuildRule>>of(ImmutableSortedSet.<BuildRule>of());
    BuildRule androidPrebuiltAar = new AndroidPrebuiltAarDescription()
        .createBuildRule(aarParams, resolver, aarArg);

    BuildRuleParams resParams = new BuildRuleParams(
        BuildTargetFactory.newInstance("//java/com/example:res"),
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(androidPrebuiltAar),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of(),
        BuildTargetPattern.PUBLIC,
        projectFilesystem,
        ruleKeyBuilderFactory,
        AndroidResourceDescription.TYPE);
    AndroidResourceDescription.Arg resArg = new AndroidResourceDescription.Arg();
    resArg.res = Optional.of(Paths.get("java/com/example/res"));
    resArg.assets = Optional.<Path>absent();
    resArg.hasWhitelistedStrings = Optional.of(Boolean.FALSE);
    resArg.rDotJavaPackage = Optional.of("com.example");
    resArg.manifest = Optional.absent();
    resArg.deps = Optional.<ImmutableSortedSet<BuildRule>>of(
        ImmutableSortedSet.<BuildRule>of(androidPrebuiltAar));
    BuildRule androidResource = new AndroidResourceDescription()
        .createBuildRule(resParams, resolver, resArg);

    ImmutableList<HasAndroidResourceDeps> resDeps = UberRDotJavaUtil.getAndroidResourceDeps(
        Collections.<BuildRule>singleton(androidResource));
    assertEquals(
        "The android_resource() rule should include the graph-enhanced android_resource() from " +
            "the android_prebuilt_aar() rule in its list of Android resource dependencies.",
        ImmutableList.of(
            "//java/com/example:res",
            "//third-party:play-services#aar_android_resource"),
        FluentIterable.from(resDeps).transform(Functions.toStringFunction()).toList());
  }
}

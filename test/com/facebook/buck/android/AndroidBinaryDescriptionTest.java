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

package com.facebook.buck.android;

import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;

public class AndroidBinaryDescriptionTest {

  @Test
  public void testNoDxRulesBecomeFirstOrderDeps() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildRule transitiveDep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build(ruleResolver);
    BuildRule dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(transitiveDep.getBuildTarget())
            .build(ruleResolver);
    Keystore keystore =
        ruleResolver.addToIndex(
            new Keystore(
                new FakeBuildRuleParamsBuilder("//:keystore").build(),
                pathResolver,
                new FakeSourcePath("store"),
                new FakeSourcePath("properties")));
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    AndroidBinary androidBinary =
        (AndroidBinary) AndroidBinaryBuilder.createBuilder(target)
            .setManifest(new FakeSourcePath("manifest.xml"))
            .setKeystore(keystore.getBuildTarget())
            .setNoDx(ImmutableSet.of(transitiveDep.getBuildTarget()))
            .setOriginalDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(ruleResolver);
    assertThat(androidBinary.getDeps(), Matchers.hasItem(transitiveDep));
  }

}

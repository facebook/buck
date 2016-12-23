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

import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Locale;

public class AndroidBinaryDescriptionTest {

  @Test
  public void testNoDxRulesBecomeFirstOrderDeps() throws Exception {
    TargetNode<?, ?> transitiveDepNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build();
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(transitiveDepNode.getBuildTarget())
            .build();
    TargetNode<?, ?> keystoreNode =
        KeystoreBuilder.createBuilder(BuildTargetFactory.newInstance("//:keystore"))
            .setStore(new FakeSourcePath("store"))
            .setProperties(new FakeSourcePath("properties"))
            .build();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    TargetNode<?, ?> androidBinaryNode =
        AndroidBinaryBuilder.createBuilder(target)
            .setManifest(new FakeSourcePath("manifest.xml"))
            .setKeystore(BuildTargetFactory.newInstance("//:keystore"))
            .setNoDx(ImmutableSet.of(transitiveDepNode.getBuildTarget()))
            .setOriginalDeps(ImmutableSortedSet.of(depNode.getBuildTarget()))
            .build();
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(transitiveDepNode, depNode, keystoreNode, androidBinaryNode);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule transitiveDep = ruleResolver.requireRule(transitiveDepNode.getBuildTarget());
    AndroidBinary androidBinary = (AndroidBinary) ruleResolver.requireRule(target);
    assertThat(androidBinary.getDeps(), Matchers.hasItem(transitiveDep));
  }

  @Test
  public void turkishCaseRulesDoNotCrashConstructor() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));
    Keystore keystore =
        ruleResolver.addToIndex(
            new Keystore(
                new FakeBuildRuleParamsBuilder("//:keystore").build(),
                pathResolver,
                new FakeSourcePath("store"),
                new FakeSourcePath("properties")));
    Locale originalLocale = Locale.getDefault();
    try {
      Locale.setDefault(new Locale("tr"));
      // Make sure this doesn't crash in Enum.valueOf() when default Turkish locale rules
      // upper-case "instrumented" to "\u0130NSTRUMENTED".
      AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//:rule"))
          .setManifest(new FakeSourcePath("manifest.xml"))
          .setKeystore(keystore.getBuildTarget())
          .setPackageType("instrumented")
          .build(ruleResolver, new FakeProjectFilesystem(), TargetGraph.EMPTY);
    } finally {
      Locale.setDefault(originalLocale);
    }
  }
}

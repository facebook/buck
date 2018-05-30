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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Locale;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidBinaryDescriptionTest {

  @Test
  public void testNoDxRulesBecomeFirstOrderDeps() {
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
            .setStore(FakeSourcePath.of("store"))
            .setProperties(FakeSourcePath.of("properties"))
            .build();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    TargetNode<?, ?> androidBinaryNode =
        AndroidBinaryBuilder.createBuilder(target)
            .setManifest(FakeSourcePath.of("manifest.xml"))
            .setKeystore(BuildTargetFactory.newInstance("//:keystore"))
            // Force no predexing.
            .setPreprocessJavaClassesBash(StringWithMacrosUtils.format("cp"))
            .setNoDx(ImmutableSet.of(transitiveDepNode.getBuildTarget()))
            .setOriginalDeps(ImmutableSortedSet.of(depNode.getBuildTarget()))
            .build();
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(transitiveDepNode, depNode, keystoreNode, androidBinaryNode);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            targetGraph, AndroidBinaryBuilder.createToolchainProviderForAndroidBinary());

    BuildRule transitiveDep = graphBuilder.requireRule(transitiveDepNode.getBuildTarget());
    graphBuilder.requireRule(target);
    BuildRule nonPredexedRule =
        graphBuilder.requireRule(
            target.withFlavors(AndroidBinaryGraphEnhancer.NON_PREDEXED_DEX_BUILDABLE_FLAVOR));
    assertThat(nonPredexedRule.getBuildDeps(), Matchers.hasItem(transitiveDep));
  }

  @Test
  public void turkishCaseRulesDoNotCrashConstructor() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:keystore");
    Keystore keystore =
        graphBuilder.addToIndex(
            new Keystore(
                buildTarget,
                new FakeProjectFilesystem(),
                TestBuildRuleParams.create(),
                FakeSourcePath.of("store"),
                FakeSourcePath.of("properties")));
    Locale originalLocale = Locale.getDefault();
    try {
      Locale.setDefault(new Locale("tr"));
      // Make sure this doesn't crash in Enum.valueOf() when default Turkish locale rules
      // upper-case "instrumented" to "\u0130NSTRUMENTED".
      AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//:rule"))
          .setManifest(FakeSourcePath.of("manifest.xml"))
          .setKeystore(keystore.getBuildTarget())
          .setPackageType("instrumented")
          .build(graphBuilder, new FakeProjectFilesystem(), TargetGraph.EMPTY);
    } finally {
      Locale.setDefault(originalLocale);
    }
  }

  @Test
  public void duplicateResourceBanningDefaultAllow() {
    AndroidBinaryDescriptionArg arg =
        AndroidBinaryDescriptionArg.builder()
            .setName("res")
            .setManifest(FakeSourcePath.of("manifest"))
            .setKeystore(BuildTargetFactory.newInstance("//:keystore"))
            .setBannedDuplicateResourceTypes(EnumSet.of(RDotTxtEntry.RType.STRING))
            .build();

    assertEquals(
        EnumSet.of(RDotTxtEntry.RType.STRING), arg.getEffectiveBannedDuplicateResourceTypes());
  }

  @Test
  public void duplicateResourceBanningDefaultBan() {
    AndroidBinaryDescriptionArg arg =
        AndroidBinaryDescriptionArg.builder()
            .setName("res")
            .setManifest(FakeSourcePath.of("manifest"))
            .setKeystore(BuildTargetFactory.newInstance("//:keystore"))
            .setDuplicateResourceBehavior(
                AndroidBinaryDescriptionArg.DuplicateResourceBehaviour.BAN_BY_DEFAULT)
            .setAllowedDuplicateResourceTypes(EnumSet.of(RDotTxtEntry.RType.STRING))
            .build();

    assertEquals(
        EnumSet.complementOf(EnumSet.of(RDotTxtEntry.RType.STRING)),
        arg.getEffectiveBannedDuplicateResourceTypes());
  }

  @Test(expected = IllegalArgumentException.class)
  public void duplicateResourceBanningBadCombinationBan() {
    AndroidBinaryDescriptionArg.builder()
        .setName("res")
        .setManifest(FakeSourcePath.of("manifest"))
        .setKeystore(BuildTargetFactory.newInstance("//:keystore"))
        .setDuplicateResourceBehavior(
            AndroidBinaryDescriptionArg.DuplicateResourceBehaviour.BAN_BY_DEFAULT)
        .setBannedDuplicateResourceTypes(EnumSet.of(RDotTxtEntry.RType.STRING))
        .build()
        .getEffectiveBannedDuplicateResourceTypes();
  }

  @Test(expected = IllegalArgumentException.class)
  public void duplicateResourceBanningBadCombinationAllow() {
    AndroidBinaryDescriptionArg.builder()
        .setName("res")
        .setManifest(FakeSourcePath.of("manifest"))
        .setKeystore(BuildTargetFactory.newInstance("//:keystore"))
        .setDuplicateResourceBehavior(
            AndroidBinaryDescriptionArg.DuplicateResourceBehaviour.ALLOW_BY_DEFAULT)
        .setAllowedDuplicateResourceTypes(EnumSet.of(RDotTxtEntry.RType.STRING))
        .build()
        .getEffectiveBannedDuplicateResourceTypes();
  }
}

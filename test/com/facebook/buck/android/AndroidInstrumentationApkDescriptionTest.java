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

import static com.facebook.buck.jvm.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_OPTIONS;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.DxToolchain;
import com.facebook.buck.android.toolchain.ndk.impl.TestNdkCxxPlatformsProviderFactory;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidInstrumentationApkDescriptionTest {

  @Test
  public void testNoDxRulesBecomeFirstOrderDeps() {
    // Build up the original APK rule.
    TargetNode<?, ?> transitiveDepNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build();
    TargetNode<?, ?> dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(transitiveDepNode.getBuildTarget())
            .build();
    TargetNode<?, ?> keystore =
        KeystoreBuilder.createBuilder(BuildTargetFactory.newInstance("//:keystore"))
            .setStore(FakeSourcePath.of("store"))
            .setProperties(FakeSourcePath.of("properties"))
            .build();
    TargetNode<?, ?> androidBinary =
        AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//:apk"))
            .setManifest(FakeSourcePath.of("manifest.xml"))
            .setKeystore(keystore.getBuildTarget())
            .setNoDx(ImmutableSet.of(transitiveDepNode.getBuildTarget()))
            .setOriginalDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    TargetNode<?, ?> androidInstrumentationApk =
        AndroidInstrumentationApkBuilder.createBuilder(target)
            .setManifest(FakeSourcePath.of("manifest.xml"))
            .setApk(androidBinary.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            transitiveDepNode, dep, keystore, androidBinary, androidInstrumentationApk);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            targetGraph, createToolchainProviderForAndroidInstrumentationApk());
    BuildRule transitiveDep = graphBuilder.requireRule(transitiveDepNode.getBuildTarget());
    graphBuilder.requireRule(target);
    BuildRule nonPredexedRule =
        graphBuilder.requireRule(
            target.withFlavors(AndroidBinaryGraphEnhancer.NON_PREDEXED_DEX_BUILDABLE_FLAVOR));
    assertThat(nonPredexedRule.getBuildDeps(), Matchers.hasItem(transitiveDep));
  }

  public static ToolchainProvider createToolchainProviderForAndroidInstrumentationApk() {
    return new ToolchainProviderBuilder()
        .withToolchain(TestNdkCxxPlatformsProviderFactory.createDefaultNdkPlatformsProvider())
        .withToolchain(
            DxToolchain.DEFAULT_NAME, DxToolchain.of(MoreExecutors.newDirectExecutorService()))
        .withToolchain(
            JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.of(ANDROID_JAVAC_OPTIONS))
        .withToolchain(
            AndroidPlatformTarget.DEFAULT_NAME, TestAndroidPlatformTargetFactory.create())
        .withToolchain(
            JavaOptionsProvider.DEFAULT_NAME,
            JavaOptionsProvider.of(DEFAULT_JAVA_OPTIONS, DEFAULT_JAVA_OPTIONS))
        .withToolchain(
            JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.of(DEFAULT_JAVAC_OPTIONS))
        .build();
  }
}

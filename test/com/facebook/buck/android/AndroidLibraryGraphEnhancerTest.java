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

package com.facebook.buck.android;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidLibraryGraphEnhancer.ResourceDependencyMode;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidLibraryGraphEnhancerTest {

  @Test
  public void testEmptyResources() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        buildTarget,
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        DEFAULT_JAVAC_OPTIONS,
        ResourceDependencyMode.FIRST_ORDER,
        Optional.<String>absent());
    Optional<DummyRDotJava> result = graphEnhancer.getBuildableForAndroidResources(
        new BuildRuleResolver(),
        /* createdBuildableIfEmptyDeps */ false);
    assertFalse(result.isPresent());
  }

  @Test
  public void testBuildRuleResolverCaching() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        buildTarget,
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        DEFAULT_JAVAC_OPTIONS,
        ResourceDependencyMode.FIRST_ORDER,
        Optional.<String>absent());
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    Optional<DummyRDotJava> result = graphEnhancer.getBuildableForAndroidResources(
        buildRuleResolver,
        /* createdBuildableIfEmptyDeps */ true);
    Optional<DummyRDotJava> secondResult = graphEnhancer.getBuildableForAndroidResources(
        buildRuleResolver,
        /* createdBuildableIfEmptyDeps */ true);
    assertThat(result.get(), Matchers.sameInstance(secondResult.get()));
  }

  @Test
  public void testBuildableIsCreated() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildRule resourceRule1 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(pathResolver)
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
            .setRDotJavaPackage("com.facebook")
            .setRes(new FakeSourcePath("android_res/com/example/res1"))
            .build());
    BuildRule resourceRule2 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(pathResolver)
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
            .setRDotJavaPackage("com.facebook")
            .setRes(new FakeSourcePath("android_res/com/example/res2"))
            .build());

    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
        .setDeclaredDeps(ImmutableSortedSet.of(resourceRule1, resourceRule2))
        .build();

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        buildTarget,
        buildRuleParams,
        DEFAULT_JAVAC_OPTIONS,
        ResourceDependencyMode.FIRST_ORDER,
        Optional.<String>absent());
    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.getBuildableForAndroidResources(
        ruleResolver,
        /* createBuildableIfEmptyDeps */ false);

    assertTrue(dummyRDotJava.isPresent());
    assertEquals(
        "DummyRDotJava must contain these exact AndroidResourceRules.",
        // Note: these are the reverse order to which they are in the buildRuleParams.
        ImmutableList.of(resourceRule1, resourceRule2),
        dummyRDotJava.get().getAndroidResourceDeps());

    assertEquals("//java/com/example:library#dummy_r_dot_java",
        dummyRDotJava.get().getFullyQualifiedName());
    assertEquals(
        "DummyRDotJava must depend on the two AndroidResourceRules.",
        ImmutableSet.of("//android_res/com/example:res1", "//android_res/com/example:res2"),
        FluentIterable.from(dummyRDotJava.get().getDeps())
            .transform(Functions.toStringFunction()).toSet());
  }

  @Test
  public void testCreatedBuildableHasOverriddenJavacConfig() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildRule resourceRule1 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(pathResolver)
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
            .setRDotJavaPackage("com.facebook")
            .setRes(new FakeSourcePath("android_res/com/example/res1"))
            .build());
    BuildRule resourceRule2 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(pathResolver)
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
            .setRDotJavaPackage("com.facebook")
            .setRes(new FakeSourcePath("android_res/com/example/res2"))
            .build());

    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
        .setDeclaredDeps(ImmutableSortedSet.of(resourceRule1, resourceRule2))
        .build();

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        buildTarget,
        buildRuleParams,
        JavacOptions.builder(ANDROID_JAVAC_OPTIONS)
            .setSourceLevel("7")
            .setTargetLevel("7")
                    .build(),
                ResourceDependencyMode.FIRST_ORDER,
        Optional.<String>absent());
    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.getBuildableForAndroidResources(
        ruleResolver,
        /* createBuildableIfEmptyDeps */ false);

    assertTrue(dummyRDotJava.isPresent());
    JavacOptions javacOptions = dummyRDotJava.get().getJavacOptions();
    assertEquals("7", javacOptions.getSourceLevel());
  }

  @Test
  public void testDummyRDotJavaRuleInheritsJavacOptionsDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeBuildRule dep =
        resolver.addToIndex(
            new FakeBuildRule(BuildTargetFactory.newInstance("//:dep"), pathResolver));
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    JavacOptions options = JavacOptions.builder()
        .setSourceLevel("5")
        .setTargetLevel("5")
        .setJavacJarPath(new BuildTargetSourcePath(dep.getBuildTarget()))
        .build();
    AndroidLibraryGraphEnhancer graphEnhancer =
        new AndroidLibraryGraphEnhancer(
            target,
            new FakeBuildRuleParamsBuilder(target).build(),
            options,
            ResourceDependencyMode.FIRST_ORDER,
            Optional.<String>absent());
    Optional<DummyRDotJava> result =
        graphEnhancer.getBuildableForAndroidResources(
            resolver,
            /* createdBuildableIfEmptyDeps */ true);
    assertTrue(result.isPresent());
    assertThat(result.get().getDeps(), Matchers.<BuildRule>hasItem(dep));
  }

}

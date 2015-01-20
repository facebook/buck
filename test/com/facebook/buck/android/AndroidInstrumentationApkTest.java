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

import static com.facebook.buck.java.JavaCompilationConstants.DEFAULT_JAVAC;
import static com.facebook.buck.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.java.FakeJavaLibrary;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class AndroidInstrumentationApkTest {

  @Test
  public void testAndroidInstrumentationApkExcludesClassesFromInstrumentedApk() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    final FakeJavaLibrary javaLibrary1 = new FakeJavaLibrary(
        BuildTarget.builder("//java/com/example", "lib1").build(), pathResolver);

    FakeJavaLibrary javaLibrary2 = new FakeJavaLibrary(
        BuildTarget.builder("//java/com/example", "lib2").build(),
        pathResolver,
        /* deps */ ImmutableSortedSet.of((BuildRule) javaLibrary1)) {
      @Override
      public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
        ImmutableSetMultimap.Builder<JavaLibrary, Path> builder =
            ImmutableSetMultimap.builder();
        builder.put(javaLibrary1, javaLibrary1.getPathToOutputFile());
        builder.put(this, this.getPathToOutputFile());
        return builder.build();
      }
    };

    final FakeJavaLibrary javaLibrary3 = new FakeJavaLibrary(
        BuildTarget.builder("//java/com/example", "lib3").build(), pathResolver);

    FakeJavaLibrary javaLibrary4 = new FakeJavaLibrary(
        BuildTarget.builder("//java/com/example", "lib4").build(),
        pathResolver,
        /* deps */ ImmutableSortedSet.of((BuildRule) javaLibrary3)) {
      @Override
      public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
        ImmutableSetMultimap.Builder<JavaLibrary, Path> builder =
            ImmutableSetMultimap.builder();
        builder.put(javaLibrary3, javaLibrary3.getPathToOutputFile());
        builder.put(this, this.getPathToOutputFile());
        return builder.build();
      }
    };

    Map<BuildTarget, BuildRule> buildRuleIndex = Maps.newHashMap();
    buildRuleIndex.put(javaLibrary1.getBuildTarget(), javaLibrary1);
    buildRuleIndex.put(javaLibrary2.getBuildTarget(), javaLibrary2);
    buildRuleIndex.put(javaLibrary3.getBuildTarget(), javaLibrary3);
    buildRuleIndex.put(javaLibrary4.getBuildTarget(), javaLibrary4);
    BuildRuleResolver ruleResolver = new BuildRuleResolver(buildRuleIndex);

    BuildRule keystore = KeystoreBuilder.createBuilder(
        BuildTarget.builder("//keystores", "debug").build())
        .setProperties(Paths.get("keystores/debug.properties"))
        .setStore(Paths.get("keystores/debug.keystore"))
        .build(ruleResolver);

    // AndroidBinaryRule transitively depends on :lib1, :lib2, and :lib3.
    AndroidBinaryBuilder androidBinaryBuilder = AndroidBinaryBuilder.createBuilder(
        BuildTarget.builder("//apps", "app").build());
    ImmutableSortedSet<BuildTarget> originalDepsTargets = ImmutableSortedSet.of(
        javaLibrary2.getBuildTarget(),
        javaLibrary3.getBuildTarget());
    androidBinaryBuilder
        .setManifest(new TestSourcePath("apps/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:18")
        .setKeystore(keystore.getBuildTarget())
        .setOriginalDeps(originalDepsTargets);
    AndroidBinary androidBinary = (AndroidBinary) androidBinaryBuilder.build(ruleResolver);

    // AndroidInstrumentationApk transitively depends on :lib1, :lib2, :lib3, and :lib4.
    ImmutableSortedSet<BuildTarget> apkOriginalDepsTargets = ImmutableSortedSet.of(
        javaLibrary2.getBuildTarget(),
        javaLibrary4.getBuildTarget());
    AndroidInstrumentationApkDescription.Arg arg = new AndroidInstrumentationApkDescription.Arg();
    arg.apk = androidBinary.getBuildTarget();
    arg.deps = Optional.of(apkOriginalDepsTargets);
    arg.manifest = new TestSourcePath("apps/InstrumentationAndroidManifest.xml");

    BuildRuleParams params = new FakeBuildRuleParamsBuilder(
        BuildTarget.builder("//apps", "instrumentation").build())
        .setDeps(ruleResolver.getAllRules(apkOriginalDepsTargets))
        .setExtraDeps(ImmutableSortedSet.<BuildRule>of(androidBinary))
        .build();
    AndroidInstrumentationApk androidInstrumentationApk = (AndroidInstrumentationApk)
        new AndroidInstrumentationApkDescription(
            new ProGuardConfig(new FakeBuckConfig()),
            DEFAULT_JAVAC,
            DEFAULT_JAVAC_OPTIONS,
            ImmutableMap.<AndroidBinary.TargetCpuType, NdkCxxPlatform>of())
                .createBuildRule(params, ruleResolver, arg);

    assertEquals(
        "//apps:app should have three JAR files to dex.",
        ImmutableSet.of(
            Paths.get("buck-out/gen/java/com/example/lib1.jar"),
            Paths.get("buck-out/gen/java/com/example/lib2.jar"),
            Paths.get("buck-out/gen/java/com/example/lib3.jar")),
        androidBinary.getAndroidPackageableCollection().classpathEntriesToDex());
    assertEquals(
        "//apps:instrumentation should have one JAR file to dex.",
        ImmutableSet.of(Paths.get("buck-out/gen/java/com/example/lib4.jar")),
        androidInstrumentationApk.getAndroidPackageableCollection().classpathEntriesToDex());
  }
}

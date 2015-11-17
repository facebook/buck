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

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AndroidInstrumentationApkTest {

  @Test
  public void testAndroidInstrumentationApkExcludesClassesFromInstrumentedApk() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    final FakeJavaLibrary javaLibrary1 = new FakeJavaLibrary(
        BuildTargetFactory.newInstance("//java/com/example:lib1"), pathResolver);

    FakeJavaLibrary javaLibrary2 = new FakeJavaLibrary(
        BuildTargetFactory.newInstance("//java/com/example:lib2"),
        pathResolver,
        /* deps */ ImmutableSortedSet.of((BuildRule) javaLibrary1)) {
      @Override
      public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
        ImmutableSetMultimap.Builder<JavaLibrary, Path> builder =
            ImmutableSetMultimap.builder();
        builder.put(javaLibrary1, javaLibrary1.getPathToOutput());
        builder.put(this, this.getPathToOutput());
        return builder.build();
      }
    };

    final FakeJavaLibrary javaLibrary3 = new FakeJavaLibrary(
        BuildTargetFactory.newInstance("//java/com/example:lib3"), pathResolver);

    FakeJavaLibrary javaLibrary4 = new FakeJavaLibrary(
        BuildTargetFactory.newInstance("//java/com/example:lib4"),
        pathResolver,
        /* deps */ ImmutableSortedSet.of((BuildRule) javaLibrary3)) {
      @Override
      public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
        ImmutableSetMultimap.Builder<JavaLibrary, Path> builder =
            ImmutableSetMultimap.builder();
        builder.put(javaLibrary3, javaLibrary3.getPathToOutput());
        builder.put(this, this.getPathToOutput());
        return builder.build();
      }
    };

    ruleResolver.addToIndex(javaLibrary1);
    ruleResolver.addToIndex(javaLibrary2);
    ruleResolver.addToIndex(javaLibrary3);
    ruleResolver.addToIndex(javaLibrary4);

    BuildRule keystore = KeystoreBuilder.createBuilder(
        BuildTargetFactory.newInstance("//keystores:debug"))
        .setProperties(new FakeSourcePath("keystores/debug.properties"))
        .setStore(new FakeSourcePath("keystores/debug.keystore"))
        .build(ruleResolver);

    // AndroidBinaryRule transitively depends on :lib1, :lib2, and :lib3.
    AndroidBinaryBuilder androidBinaryBuilder = AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//apps:app"));
    ImmutableSortedSet<BuildTarget> originalDepsTargets = ImmutableSortedSet.of(
        javaLibrary2.getBuildTarget(),
        javaLibrary3.getBuildTarget());
    androidBinaryBuilder
        .setManifest(new FakeSourcePath("apps/AndroidManifest.xml"))
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
    arg.manifest = new FakeSourcePath("apps/InstrumentationAndroidManifest.xml");

    BuildRuleParams params = new FakeBuildRuleParamsBuilder(
        BuildTargetFactory.newInstance("//apps:instrumentation"))
        .setDeclaredDeps(ruleResolver.getAllRules(apkOriginalDepsTargets))
        .setExtraDeps(ImmutableSortedSet.<BuildRule>of(androidBinary))
        .build();
    AndroidInstrumentationApk androidInstrumentationApk = (AndroidInstrumentationApk)
        new AndroidInstrumentationApkDescription(
            new ProGuardConfig(FakeBuckConfig.builder().build()),
            DEFAULT_JAVAC_OPTIONS,
            ImmutableMap.<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform>of(),
            MoreExecutors.newDirectExecutorService())
            .createBuildRule(TargetGraph.EMPTY, params, ruleResolver, arg);

    assertEquals(
        "//apps:app should have three JAR files to dex.",
        ImmutableSet.of(
            Paths.get("buck-out/gen/java/com/example/lib1.jar"),
            Paths.get("buck-out/gen/java/com/example/lib2.jar"),
            Paths.get("buck-out/gen/java/com/example/lib3.jar")),
        FluentIterable
            .from(androidBinary.getAndroidPackageableCollection().getClasspathEntriesToDex())
            .transform(pathResolver.deprecatedPathFunction())
            .toSet());
    assertEquals(
        "//apps:instrumentation should have one JAR file to dex.",
        ImmutableSet.of(Paths.get("buck-out/gen/java/com/example/lib4.jar")),
        FluentIterable
            .from(
                androidInstrumentationApk
                    .getAndroidPackageableCollection()
                    .getClasspathEntriesToDex())
            .transform(pathResolver.deprecatedPathFunction())
            .toSet());
  }
}

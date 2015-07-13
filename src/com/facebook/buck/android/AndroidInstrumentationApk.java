/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.nio.file.Path;
import java.util.EnumSet;


/**
 * Apk that functions as a test package in Android.
 * <p>
 * Android's
 * <a href="http://developer.android.com/tools/testing/testing_android.html">
 *   Testing Fundamentals</a>
 * documentation includes a diagram that shows the relationship between an "application package" and
 * a "test package" when running a test. This corresponds to a test package. Note that a test
 * package has an interesting quirk where it is <em>compiled against</em> an application package,
 * but <em>must not include</em> the resources or Java classes of the application package.
 * Therefore, this class takes responsibility for making sure the appropriate bits are excluded.
 * Failing to do so will generate mysterious runtime errors when running the test.
 */
public class AndroidInstrumentationApk extends AndroidBinary {

  AndroidInstrumentationApk(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Optional<Path> proGuardJarOverride,
      String proGuardMaxHeapSize,
      AndroidBinary apkUnderTest,
      ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex,
      AndroidGraphEnhancementResult enhancementResult,
      ListeningExecutorService dxExecutorService) {
    super(
        buildRuleParams,
        resolver,
        proGuardJarOverride,
        proGuardMaxHeapSize,
        apkUnderTest.getKeystore(),
        PackageType.INSTRUMENTED,
        // Do not split the test apk even if the tested apk is split
        DexSplitMode.NO_SPLIT,
        apkUnderTest.getBuildTargetsToExcludeFromDex(),
        apkUnderTest.getSdkProguardConfig(),
        apkUnderTest.getOptimizationPasses(),
        apkUnderTest.getProguardConfig(),
        apkUnderTest.getResourceCompressionMode(),
        apkUnderTest.getCpuFilters(),
        apkUnderTest.getResourceFilter(),
        EnumSet.noneOf(ExopackageMode.class),
        apkUnderTest.getMacroExpander(),
        // preprocessJavaClassBash is not supported in instrumentation
        Optional.<String>absent(),
        rulesToExcludeFromDex,
        enhancementResult,
        // reordering is not supported in instrumentation. TODO(user): add support
        Optional.<Boolean>absent(),
        Optional.<SourcePath>absent(),
        Optional.<SourcePath>absent(),
        Optional.<Integer>absent(),
        dxExecutorService);
  }
}

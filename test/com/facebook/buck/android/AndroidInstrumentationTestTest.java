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

import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.android.exopackage.ExopackageInfo.DexInfo;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.json.ObjectMappers;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class AndroidInstrumentationTestTest {
  @Test
  public void testFilterBasics() {
    assertEquals(
        Optional.<String>empty(),
        AndroidInstrumentationTest.getFilterString(TestRunningOptions.builder().build()));

    assertEquals(
        Optional.of("FooBar#method"),
        AndroidInstrumentationTest.getFilterString(
            TestRunningOptions.builder()
                .setTestSelectorList(
                    TestSelectorList.builder().addRawSelectors("FooBar#method").build())
                .build()));

    assertEquals(
        Optional.of("com.foo.FooBar"),
        AndroidInstrumentationTest.getFilterString(
            TestRunningOptions.builder()
                .setTestSelectorList(
                    TestSelectorList.builder().addRawSelectors("com.foo.FooBar").build())
                .build()));
  }

  @Test
  public void testExternalPathContents() throws Exception {
    ProjectFilesystem fakeFilesystem = new FakeProjectFilesystem();
    HasInstallableApk apk =
        new HasInstallableApk() {
          @Override
          public ApkInfo getApkInfo() {
            return ApkInfo.builder()
                .setApkPath(PathSourcePath.of(fakeFilesystem, Paths.get("ApkInfo")))
                .setManifestPath(PathSourcePath.of(fakeFilesystem, Paths.get("AndroidManifest")))
                .setExopackageInfo(
                    ExopackageInfo.builder()
                        .setDexInfo(
                            DexInfo.builder()
                                .setMetadata(
                                    PathSourcePath.of(fakeFilesystem, Paths.get("metadata")))
                                .setDirectory(
                                    PathSourcePath.of(fakeFilesystem, Paths.get("dexInfoDir")))
                                .build())
                        .build())
                .build();
          }

          @Override
          public BuildTarget getBuildTarget() {
            return BuildTargetFactory.newInstance("//:instrumentation_test");
          }

          @Override
          public ProjectFilesystem getProjectFilesystem() {
            return fakeFilesystem;
          }
        };
    String result =
        ObjectMappers.WRITER.writeValueAsString(
            ExternalTestRunnerTestSpec.builder()
                .setTarget(apk.getBuildTarget())
                .setType("android_instrumentation")
                .setRequiredPaths(
                    AndroidInstrumentationTest.getRequiredPaths(
                        apk,
                        Optional.of(Paths.get("instrumentationApk")),
                        Optional.of(Paths.get("apkUnderTest"))))
                .build());

    String outputPath =
        MorePaths.pathWithPlatformSeparators(
            Paths.get("buck-out", "bin", "__instrumentation_test__exopackage_dir__")
                .toAbsolutePath());
    String jsonEncodedValue = ObjectMappers.WRITER.writeValueAsString(outputPath);
    assertEquals(
        "{\"target\":\"//:instrumentation_test\","
            + "\"type\":\"android_instrumentation\","
            + "\"command\":[],\"env\":{},"
            + "\"required_paths\":[\"apkUnderTest\",\"instrumentationApk\","
            + jsonEncodedValue
            + "],"
            + "\"labels\":[],\"contacts\":[]}",
        result);
  }
}

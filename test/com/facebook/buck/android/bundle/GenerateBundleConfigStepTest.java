/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.android.bundle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.bundle.Config.BundleConfig;
import com.android.bundle.Config.Bundletool;
import com.android.bundle.Config.Compression;
import com.android.bundle.Config.Optimizations;
import com.android.bundle.Config.SplitDimension;
import com.android.bundle.Config.SplitsConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import java.nio.file.Path;
import org.junit.Test;

public class GenerateBundleConfigStepTest {

  public void assertSplitDimension(SplitDimension splitdimension) {
    assertEquals(0, splitdimension.getValueValue());
    assertFalse(splitdimension.getNegate());
  }

  public void assertSplitsConfig(SplitsConfig splitsconfig) {
    assertEquals(1, splitsconfig.getSplitDimensionCount());
    assertSplitDimension(splitsconfig.getSplitDimension(0));
  }

  public void assertOptimizations(Optimizations optimizations) {
    assertTrue(optimizations.hasSplitsConfig());
    assertSplitsConfig(optimizations.getSplitsConfig());
  }

  public void assertCompression(Compression compression) {
    assertEquals(0, compression.getUncompressedGlobCount());
  }

  public void assertBundletool(Bundletool bundletool) {
    assertEquals("", bundletool.getVersion());
  }

  @Test
  public void testBundleConfig() throws Exception {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path path = projectFilesystem.resolve("output");

    GenerateBundleConfigStep generateconfigstep =
        new GenerateBundleConfigStep(projectFilesystem, path);
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    generateconfigstep.execute(executionContext);

    BundleConfig bundleConfig =
        BundleConfig.parseFrom(
            projectFilesystem.newFileInputStream(path.resolve("BundleConfig.pb")));

    assertTrue(bundleConfig.hasBundletool());
    assertBundletool(bundleConfig.getBundletool());

    assertTrue(bundleConfig.hasOptimizations());
    assertOptimizations(bundleConfig.getOptimizations());

    assertTrue(bundleConfig.hasCompression());
    assertCompression(bundleConfig.getCompression());
  }
}

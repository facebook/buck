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

package com.facebook.buck.apple.endtoend;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.endtoend.ConfigSetBuilder;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.facebook.buck.util.environment.Platform;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * E2E tests for buck's building process on an environment constructed like:
 *
 * <pre>
 *                                            apple_package
 *                                                 +
 *                                                 |
 *                                                 v
 *                                            apple_bundle
 *                                                 +
 *                     +---------------------------+----------------------------+
 *                     v                                                        v
 *                apple_binary                                           genrule (plist)
 *                     +                                                        +
 *      +------------------------------------+-----------------+                |
 *      v              v                     v                 v                v
 * cxx_library    apple_resource   apple_asset_library   apple_library    python_binary
 * </pre>
 */
@RunWith(EndToEndRunner.class)
public class AppleEndToEndTest {
  private static final String mainTarget = "//ios:BuckDemoApp";

  @Before
  public void assumeEnvironment() {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.IPHONEOS));
  }

  @Environment
  public static EndToEndEnvironment baseEnvironment() {
    ConfigSetBuilder configSetBuilder = new ConfigSetBuilder();
    return new EndToEndEnvironment()
        .addTemplates("mobile")
        .withCommand("build")
        .addLocalConfigSet(configSetBuilder.build())
        .addLocalConfigSet(configSetBuilder.addShlibConfigSet().build())
        .withTargets(mainTarget);
  }

  /** Determines that buck successfully outputs proper programs */
  @Test
  public void shouldBuild(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Exception {
    ProcessResult result = workspace.runBuckCommand(test);
    result.assertSuccess("Did not successfully build");
  }
}

/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

public class AppleNativeIntegrationTestUtils {

  private AppleNativeIntegrationTestUtils() {}

  public static boolean isApplePlatformAvailable(final ApplePlatform platform) {
    BuckConfig buckConfig = new FakeBuckConfig();
    AppleConfig appleConfig = new AppleConfig(buckConfig);
    Console console = new TestConsole();
    ProcessExecutor executor = new ProcessExecutor(console);
    return Iterables.any(
        appleConfig.getAppleSdkPaths(executor).keySet(),
        new Predicate<AppleSdk>() {
          @Override
          public boolean apply(AppleSdk sdk) {
            return sdk.getApplePlatform() == platform;
          }
        });
  }

}

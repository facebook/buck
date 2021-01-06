/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple.simulator;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.junit.Test;

/** Unit tests for {@link SimctlListOutputParsing}. */
public class AppleSimulatorProfileParsingTest {
  @Test
  public void iphone5sProfileParses() throws IOException {
    Optional<AppleSimulatorProfile> simulatorProfile;
    try (InputStream in =
        getClass()
            .getResourceAsStream(
                "testdata/Developer/Library/CoreSimulator/Profiles/DeviceTypes/"
                    + "iPhone 5s.simdevicetype/Contents/Resources/profile.plist")) {
      simulatorProfile = AppleSimulatorProfileParsing.parseProfilePlistStream(in);
    }

    AppleSimulatorProfile expected =
        ImmutableAppleSimulatorProfile.builder()
            .addSupportedProductFamilyIDs(1)
            .addSupportedArchitectures("i386", "x86_64")
            .build();

    assertThat(simulatorProfile, is(equalTo(Optional.of(expected))));
  }
}

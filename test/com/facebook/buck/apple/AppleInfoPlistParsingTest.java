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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** Unit tests for {@link AppleInfoPlistParsing}. */
public class AppleInfoPlistParsingTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void infoPlistParsingReturnsBundleID() throws IOException {
    Optional<String> bundleID;
    try (InputStream in =
        getClass().getResourceAsStream("testdata/simple_application_bundle_no_debug/Info.plist")) {
      Preconditions.checkState(in != null);
      bundleID = AppleInfoPlistParsing.getBundleIdFromPlistStream(Paths.get("Test"), in);
    }

    assertThat(bundleID, is(equalTo(Optional.of("com.example.DemoApp"))));
  }

  @Test
  public void failedInfoPlistParsingReturnsAbsent() throws IOException {
    Optional<String> bundleID;
    try (InputStream in = getClass().getResourceAsStream("testdata/ios-project/version.plist")) {
      Preconditions.checkState(in != null);
      bundleID = AppleInfoPlistParsing.getBundleIdFromPlistStream(Paths.get("Test"), in);
    }

    assertThat(bundleID, is(equalTo(Optional.empty())));
  }

  @Test
  public void testEmptyFileInfoPlist() throws IOException {
    InputStream in = getClass().getResourceAsStream("testdata/broken_info_plist/Broken-Info.plist");
    Preconditions.checkState(in != null);
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString("Test: the content of the plist is invalid or empty."));
    AppleInfoPlistParsing.getBundleIdFromPlistStream(Paths.get("Test"), in);
  }
}

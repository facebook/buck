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

import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeTrue;

import com.dd.plist.NSDate;
import com.facebook.buck.model.Pair;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.nio.file.Path;

/**
 * Unit tests for {@link ProvisioningProfileMetadata}.
 *
 * How to create a fake provisioning profile for unit tests
 *
 * A .mobileprovision file is simply a XML plist with a cryptographically-signed wrapper.  A real
 * profile would be signed by Apple.  For unit tests, we need to have something that decodes
 * properly but we don't care who signs it.
 *
 * First, you'll want to create a fake signing identity.  Do this in
 *
 * Keychain Access > Certificate Assistant > Create a Certificate
 * 1. Pick a name, e.g. "Fake codesigning"
 * 2. Check "Let me override defaults".
 * 3. Continue, and fill in a bogus name/email address where it asks for them.
 * Otherwise, just accept the defaults.
 *
 * Then:
 * 1. Make a XML .plist with the expected contents.
 * 2. {@code /usr/bin/security cms -S -N "Fake codesigning" -i file.plist -o file.mobileprovision}
 *
 * Of course, the file will be unusable on an actual device, but is good enough for unit testing.
 */
public class ProvisioningProfileMetadataTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testParseProvisioningProfileFile() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    Path testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles");
    Path testFile = testdataDir.resolve("sample.mobileprovision");

    ProvisioningProfileMetadata data =
        ProvisioningProfileMetadata.fromProvisioningProfilePath(testFile);

    assertThat(data.getExpirationDate(), is(equalTo(new NSDate("9999-03-05T01:33:40Z").getDate())));
    assertThat(data.getAppID(), is(equalTo(new Pair<>("ABCDE12345", "com.example.TestApp"))));
    assertThat(data.getUUID(), is(equalTo("00000000-0000-0000-0000-000000000000")));
    assertThat(data.getProfilePath().get(), is(equalTo(testFile)));

    thrown.expect(HumanReadableException.class);
    ProvisioningProfileMetadata.fromProvisioningProfilePath(
        testdataDir.resolve("invalid.mobileprovision"));
  }

  @Test
  public void testSplitAppID() throws Exception {
    Pair<String, String> result;

    result = ProvisioningProfileMetadata.splitAppID("ABCDE12345.com.example.TestApp");
    assertThat(result, is(equalTo(new Pair<>("ABCDE12345", "com.example.TestApp"))));

    result = ProvisioningProfileMetadata.splitAppID("ABCDE12345.*");
    assertThat(result, is(equalTo(new Pair<>("ABCDE12345", "*"))));

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Malformed app ID: invalid.");
    ProvisioningProfileMetadata.splitAppID("invalid.");
  }
}

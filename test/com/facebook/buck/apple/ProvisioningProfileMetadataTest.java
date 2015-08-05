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

import com.dd.plist.NSDate;
import com.facebook.buck.model.Pair;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.nio.file.Path;

/**
 * Unit tests for {@link ProvisioningProfileMetadata}.
 */
public class ProvisioningProfileMetadataTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testParseProvisioningProfileFile() throws Exception {
    Path testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles");
    Path testFile = testdataDir.resolve("sample.mobileprovision");

    ProvisioningProfileMetadata data = ProvisioningProfileMetadata.fromProvisioningProfilePath(
        testFile);

    assertThat(data.getExpirationDate(), is(equalTo(new NSDate("9999-03-05T01:33:40Z").getDate())));
    assertThat(data.getAppID(), is(equalTo(new Pair<>("ABCDE12345", "com.example.TestApp"))));
    assertThat(data.getUUID(), is(equalTo("00000000-0000-0000-0000-000000000000")));
    assertThat(data.getProfilePath().get(), is(equalTo(testFile)));

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Malformed .mobileprovision file (could not find embedded plist)");
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

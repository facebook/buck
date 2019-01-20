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

package com.facebook.buck.apple.toolchain;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.util.types.Pair;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ProvisioningProfileMetadataTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testSplitAppID() {
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

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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.util.Date;

public class ProvisioningProfileStoreTest {
  private static ProvisioningProfileMetadata makeTestMetadata(
      String appID, Date expirationDate, String uuid) throws Exception {
    return ProvisioningProfileMetadata.builder()
        .setAppID(ProvisioningProfileMetadata.splitAppID(appID))
        .setExpirationDate(expirationDate)
        .setUUID(uuid)
        .build();
  }

  @Test
  public void testExpiredProfilesAreIgnored() throws Exception {
    ProvisioningProfileStore profiles = ProvisioningProfileStore.fromProvisioningProfiles(
        ImmutableList.of(
            makeTestMetadata(
                "AAAAAAAAAA.*",
                new Date(0),
                "00000000-0000-0000-0000-000000000000")));

    Optional<ProvisioningProfileMetadata> actual =
        profiles.getBestProvisioningProfile("com.facebook.test", Optional.<String>absent());

    assertThat(actual, is(equalTo(Optional.<ProvisioningProfileMetadata>absent())));
  }

  @Test
  public void testPrefixOverride() throws Exception {
    ProvisioningProfileMetadata expected =
        makeTestMetadata("AAAAAAAAAA.*", new Date(Long.MAX_VALUE),
            "00000000-0000-0000-0000-000000000000");

    ProvisioningProfileStore profiles = ProvisioningProfileStore.fromProvisioningProfiles(
        ImmutableList.of(
            expected,
            makeTestMetadata("BBBBBBBBBB.com.facebook.test", new Date(Long.MAX_VALUE),
                "00000000-0000-0000-0000-000000000000")));

    Optional<ProvisioningProfileMetadata> actual =
        profiles.getBestProvisioningProfile("com.facebook.test", Optional.of("AAAAAAAAAA"));

    assertThat(actual.get(), is(equalTo(expected)));
  }

  @Test
  public void testGetByUUID() throws Exception {
    ProvisioningProfileMetadata expected =
        makeTestMetadata("BBBBBBBBBB.*", new Date(Long.MAX_VALUE),
            "11111111-1111-1111-1111-111111111111");

    ProvisioningProfileStore profiles = ProvisioningProfileStore.fromProvisioningProfiles(
        ImmutableList.of(
            expected,
            makeTestMetadata(
                "BBBBBBBBBB.com.facebook.test", new Date(Long.MAX_VALUE),
                "00000000-0000-0000-0000-000000000000")));

    Optional<ProvisioningProfileMetadata> actual =
        profiles.getProvisioningProfileByUUID("11111111-1111-1111-1111-111111111111");

    assertThat(actual.get(), is(equalTo(expected)));
  }

  @Test
  public void testMatchesSpecificApp() throws Exception {
    ProvisioningProfileMetadata expected =
        makeTestMetadata("BBBBBBBBBB.com.facebook.test", new Date(Long.MAX_VALUE),
            "00000000-0000-0000-0000-000000000000");

    ProvisioningProfileStore profiles = ProvisioningProfileStore.fromProvisioningProfiles(
        ImmutableList.of(
            expected,
            makeTestMetadata(
                "BBBBBBBBBB.com.facebook.*",
                new Date(Long.MAX_VALUE),
                "11111111-1111-1111-1111-111111111111")));

    Optional<ProvisioningProfileMetadata> actual =
        profiles.getBestProvisioningProfile("com.facebook.test", Optional.<String>absent());

    assertThat(actual.get(), is(equalTo(expected)));
  }

  @Test
  public void testMatchesWildcard() throws Exception {
    ProvisioningProfileMetadata expected =
        makeTestMetadata("BBBBBBBBBB.*", new Date(Long.MAX_VALUE),
            "00000000-0000-0000-0000-000000000000");

    ProvisioningProfileStore profiles = ProvisioningProfileStore.fromProvisioningProfiles(
        ImmutableList.of(expected));

    Optional<ProvisioningProfileMetadata> actual =
        profiles.getBestProvisioningProfile("com.facebook.test", Optional.<String>absent());

    assertThat(actual.get(), is(equalTo(expected)));
  }

}

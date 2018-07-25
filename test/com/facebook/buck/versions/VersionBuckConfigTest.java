/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.versions;

import static org.junit.Assert.assertThat;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.Matchers;
import org.hamcrest.junit.ExpectedException;
import org.junit.Rule;
import org.junit.Test;

public class VersionBuckConfigTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void simple() {
    VersionBuckConfig config =
        new VersionBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "version_universes",
                        ImmutableMap.of(
                            "universe1", "//hello:world=1, //foo:bar=3",
                            "universe2", "//foo:bar=2")))
                .build());
    assertThat(
        config.getVersionUniverses(),
        Matchers.equalTo(
            ImmutableMap.of(
                "universe1",
                VersionUniverse.of(
                    ImmutableMap.of(
                        BuildTargetFactory.newInstance("//hello:world"),
                        Version.of("1"),
                        BuildTargetFactory.newInstance("//foo:bar"),
                        Version.of("3"))),
                "universe2",
                VersionUniverse.of(
                    ImmutableMap.of(
                        BuildTargetFactory.newInstance("//foo:bar"), Version.of("2"))))));
  }

  @Test
  public void invalidTargetVersionPair() {
    VersionBuckConfig config =
        new VersionBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "version_universes", ImmutableMap.of("invalid", "//hello:world")))
                .build());
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("must specify version selections as a comma-separated");
    config.getVersionUniverses();
  }
}

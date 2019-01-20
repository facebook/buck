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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class VersionUniverseVersionSelectorTest {

  private static final Version ONE = Version.of("1.0");
  private static final Version TWO = Version.of("2.0");

  @Test
  public void validImplication() throws Exception {
    TargetNode<?> root = new VersionRootBuilder("//:root").setVersionUniverse("universe").build();
    BuildTarget versioned1 = BuildTargetFactory.newInstance("//:versioned1");
    BuildTarget versioned2 = BuildTargetFactory.newInstance("//:versioned2");
    VersionUniverseVersionSelector selector =
        new VersionUniverseVersionSelector(
            TargetGraphFactory.newInstance(root),
            ImmutableMap.of(
                "universe",
                VersionUniverse.builder()
                    .putVersions(versioned1, ONE)
                    .putVersions(versioned2, ONE)
                    .build()));
    ImmutableMap<BuildTarget, Version> versions =
        selector.resolve(
            root.getBuildTarget(),
            ImmutableMap.of(
                versioned1, ImmutableSet.of(ONE, TWO),
                versioned2, ImmutableSet.of(ONE, TWO)));
    assertThat(versions, Matchers.equalTo(ImmutableMap.of(versioned1, ONE, versioned2, ONE)));
  }

  @Test
  public void unusedImplication() throws Exception {
    TargetNode<?> root = new VersionRootBuilder("//:root").setVersionUniverse("universe").build();
    BuildTarget versioned1 = BuildTargetFactory.newInstance("//:versioned1");
    BuildTarget versioned2 = BuildTargetFactory.newInstance("//:versioned2");
    VersionUniverseVersionSelector selector =
        new VersionUniverseVersionSelector(
            TargetGraphFactory.newInstance(root),
            ImmutableMap.of(
                "universe",
                VersionUniverse.builder()
                    .putVersions(versioned1, ONE)
                    .putVersions(versioned2, ONE)
                    .build()));
    ImmutableMap<BuildTarget, Version> versions =
        selector.resolve(
            root.getBuildTarget(),
            ImmutableMap.of(
                versioned1, ImmutableSet.of(ONE, TWO),
                versioned2, ImmutableSet.of(ONE, TWO)));
    assertThat(versions, Matchers.equalTo(ImmutableMap.of(versioned1, ONE, versioned2, ONE)));
  }

  @Test
  public void firstConfiguredVersionUniverseUsedByDefault() {
    TargetNode<?> root = new VersionRootBuilder("//:root").build();
    VersionUniverseVersionSelector selector =
        new VersionUniverseVersionSelector(
            TargetGraphFactory.newInstance(root),
            ImmutableMap.of(
                "universe1",
                VersionUniverse.of(ImmutableMap.of()),
                "universe2",
                VersionUniverse.of(ImmutableMap.of())));
    assertThat(selector.getVersionUniverse(root).get().getKey(), Matchers.equalTo("universe1"));
  }
}

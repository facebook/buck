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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Test;

public class VersionMatchedCollectionTest {

  @Test
  public void test() {
    BuildTarget a = BuildTargetFactory.newInstance("//:a");
    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    Version v1 = Version.of("1.0");
    Version v2 = Version.of("2.0");
    VersionMatchedCollection<String> collection =
        VersionMatchedCollection.<String>builder()
            .add(ImmutableMap.of(a, v1), "a-1.0")
            .add(ImmutableMap.of(a, v2), "a-2.0")
            .add(ImmutableMap.of(b, v1), "b-1.0")
            .add(ImmutableMap.of(b, v2), "b-2.0")
            .build();
    assertThat(
        collection.getMatchingValues(ImmutableMap.of(a, v1)),
        Matchers.equalTo(ImmutableList.of("a-1.0", "b-1.0", "b-2.0")));
    assertThat(
        collection.getMatchingValues(ImmutableMap.of(a, v2)),
        Matchers.equalTo(ImmutableList.of("a-2.0", "b-1.0", "b-2.0")));
    assertThat(
        collection.getMatchingValues(ImmutableMap.of(b, v1)),
        Matchers.equalTo(ImmutableList.of("a-1.0", "a-2.0", "b-1.0")));
    assertThat(
        collection.getMatchingValues(ImmutableMap.of(b, v2)),
        Matchers.equalTo(ImmutableList.of("a-1.0", "a-2.0", "b-2.0")));
    assertThat(
        collection.getMatchingValues(ImmutableMap.of(a, v1, b, v1)),
        Matchers.equalTo(ImmutableList.of("a-1.0", "b-1.0")));
    assertThat(
        collection.getMatchingValues(ImmutableMap.of(a, v1, b, v2)),
        Matchers.equalTo(ImmutableList.of("a-1.0", "b-2.0")));
    assertThat(
        collection.getMatchingValues(ImmutableMap.of(a, v2, b, v1)),
        Matchers.equalTo(ImmutableList.of("a-2.0", "b-1.0")));
    assertThat(
        collection.getMatchingValues(ImmutableMap.of(a, v2, b, v2)),
        Matchers.equalTo(ImmutableList.of("a-2.0", "b-2.0")));
  }

}

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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.hasher.StringRuleKeyHasher;
import com.facebook.buck.testutil.DummyFileHashCache;
import com.google.common.collect.ImmutableMap;
import java.util.function.Function;
import org.junit.Test;

/** Test functionality for the ManifestEntries class that goes beyond basic key/value get/set */
public class ManifestEntriesTest {

  @Test
  public void hasAnyRespectsAllParams() {
    assertFalse(ManifestEntries.builder().build().hasAny());
    assertTrue(ManifestEntries.builder().setMinSdkVersion(1).build().hasAny());
    assertTrue(ManifestEntries.builder().setTargetSdkVersion(1).build().hasAny());
    assertTrue(ManifestEntries.builder().setVersionCode(1).build().hasAny());
    assertTrue(ManifestEntries.builder().setVersionName("").build().hasAny());
    assertTrue(ManifestEntries.builder().setDebugMode(false).build().hasAny());
    assertTrue(
        ManifestEntries.builder()
            .setPlaceholders(ImmutableMap.of("key1", "val1"))
            .build()
            .hasAny());
  }

  @Test
  public void shouldUpdateRuleKey() {
    ManifestEntries entries =
        ManifestEntries.builder()
            .setMinSdkVersion(5)
            .setTargetSdkVersion(7)
            .setVersionCode(11)
            .setVersionName("thirteen")
            .build();

    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), new TestActionGraphBuilder());

    Function<AddsToRuleKey, String> computeKey =
        value -> factory.buildForDiagnostics(value, new StringRuleKeyHasher()).diagKey;

    assertNotEquals(
        computeKey.apply(entries),
        computeKey.apply(ManifestEntries.builder().from(entries).setMinSdkVersion(10).build()));

    assertNotEquals(
        computeKey.apply(entries),
        computeKey.apply(ManifestEntries.builder().from(entries).setTargetSdkVersion(10).build()));

    assertNotEquals(
        computeKey.apply(entries),
        computeKey.apply(ManifestEntries.builder().from(entries).setVersionCode(10).build()));

    assertNotEquals(
        computeKey.apply(entries),
        computeKey.apply(ManifestEntries.builder().from(entries).setVersionName("10").build()));

    assertEquals(
        computeKey.apply(entries),
        computeKey.apply(ManifestEntries.builder().from(entries).build()));
  }
}

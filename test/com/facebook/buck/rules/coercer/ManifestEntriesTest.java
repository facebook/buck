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
package com.facebook.buck.rules.coercer;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
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

    RuleKeyObjectSink ruleKeyBuilder = createMock(RuleKeyObjectSink.class);

    expect(ruleKeyBuilder.setReflectively("minSdkVersion", Optional.of(5)))
        .andReturn(ruleKeyBuilder);
    expect(ruleKeyBuilder.setReflectively("targetSdkVersion", Optional.of(7)))
        .andReturn(ruleKeyBuilder);
    expect(ruleKeyBuilder.setReflectively("versionCode", Optional.of(11)))
        .andReturn(ruleKeyBuilder);
    expect(ruleKeyBuilder.setReflectively("versionName", Optional.of("thirteen")))
        .andReturn(ruleKeyBuilder);
    expect(ruleKeyBuilder.setReflectively("debugMode", Optional.empty())).andReturn(ruleKeyBuilder);
    expect(ruleKeyBuilder.setReflectively("placeholders", Optional.empty()))
        .andReturn(ruleKeyBuilder);

    replay(ruleKeyBuilder);

    ManifestEntries entries =
        ManifestEntries.builder()
            .setMinSdkVersion(5)
            .setTargetSdkVersion(7)
            .setVersionCode(11)
            .setVersionName("thirteen")
            .build();

    // The appendToRuleKey should set both present and absent properties
    entries.appendToRuleKey(ruleKeyBuilder);
  }
}

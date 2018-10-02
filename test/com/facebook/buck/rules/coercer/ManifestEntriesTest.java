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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.rules.keys.AlterRuleKeys;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
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

    AtomicBoolean minSdkVersionSet = new AtomicBoolean(false);
    AtomicBoolean targetSdkVersionSet = new AtomicBoolean(false);
    AtomicBoolean versionCodeSet = new AtomicBoolean(false);
    AtomicBoolean versionNameSet = new AtomicBoolean(false);
    AtomicBoolean debugModeSet = new AtomicBoolean(false);
    AtomicBoolean placeholdersSet = new AtomicBoolean(false);
    AtomicBoolean classSet = new AtomicBoolean(false);

    RuleKeyObjectSink ruleKeyBuilder =
        new RuleKeyObjectSink() {
          @Override
          public RuleKeyObjectSink setReflectively(String key, @Nullable Object val) {
            if ("minSdkVersion".equals(key)) {
              assertEquals(OptionalInt.of(5), val);
              minSdkVersionSet.set(true);
              return this;
            } else if ("targetSdkVersion".equals(key)) {
              assertEquals(OptionalInt.of(7), val);
              targetSdkVersionSet.set(true);
              return this;
            } else if ("versionCode".equals(key)) {
              assertEquals(OptionalInt.of(11), val);
              versionCodeSet.set(true);
              return this;
            } else if ("versionName".equals(key)) {
              assertEquals(Optional.of("thirteen"), val);
              versionNameSet.set(true);
              return this;
            } else if ("debugMode".equals(key)) {
              assertEquals(Optional.empty(), val);
              debugModeSet.set(true);
              return this;
            } else if ("placeholders".equals(key)) {
              assertEquals(Optional.empty(), val);
              placeholdersSet.set(true);
              return this;
            } else if (".class".equals(key)) {
              assertEquals(ManifestEntries.class.getCanonicalName(), val);
              classSet.set(true);
              return this;
            }
            throw new IllegalArgumentException(key);
          }

          @Override
          public RuleKeyObjectSink setPath(Path absolutePath, Path ideallyRelative) {
            throw new UnsupportedOperationException();
          }
        };

    ManifestEntries entries =
        ManifestEntries.builder()
            .setMinSdkVersion(5)
            .setTargetSdkVersion(7)
            .setVersionCode(11)
            .setVersionName("thirteen")
            .build();

    // The appendToRuleKey should set both present and absent properties
    AlterRuleKeys.amendKey(ruleKeyBuilder, entries);

    assertTrue(
        minSdkVersionSet.get()
            && targetSdkVersionSet.get()
            && versionCodeSet.get()
            && versionNameSet.get()
            && debugModeSet.get()
            && placeholdersSet.get()
            && classSet.get());
  }
}

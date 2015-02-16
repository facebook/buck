/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;

import org.ini4j.Ini;
import org.ini4j.Profile.Section;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

/**
 * Unit test to verify expectations about how the API for {@link Ini} works, as many of its methods
 * lack proper Javadoc.
 */
public class IniTest {

  @Test
  public void testSecondaryLoadOverridesOriginalDefs()
      throws IOException {
    Ini ini = new Ini();
    Reader originalInput = new StringReader(Joiner.on("\n").join(
        "[alias]",
        "  buck = //src/com/facebook/buck/cli:cli",
        "[cache]",
        "  mode = dir"));
    Reader overrideInput = new StringReader(Joiner.on("\n").join(
        "[alias]",
        "  test_util = //test/com/facebook/buck/util:util",
        "[cache]",
        "  mode ="));
    ini.load(originalInput);
    ini.load(overrideInput);

    Section aliasSection = ini.get("alias");
    assertEquals(
        "Should be the union of the two [alias] sections.",
        ImmutableMap.of(
            "buck", "//src/com/facebook/buck/cli:cli",
            "test_util", "//test/com/facebook/buck/util:util"),
        aliasSection);

    Section cacheSection = ini.get("cache");
    assertEquals(
        "Values from overrideInput should supercede those from originalInput, as appropriate.",
        ImmutableMap.of("mode", ""),
        cacheSection);
  }
}

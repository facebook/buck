/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public class PatternsMatcherTest {

  @Test
  public void testMatchesPattern() {
    PatternsMatcher patternsMatcher =
        new PatternsMatcher(Arrays.asList("pattern.*", "test_pattern"));

    assertTrue(patternsMatcher.matches("pattern"));
    assertTrue(patternsMatcher.matches("test_pattern"));
  }

  @Test
  public void testDoesNotMatchPattern() {
    PatternsMatcher patternsMatcher =
        new PatternsMatcher(Arrays.asList("pattern.*", "test_pattern"));

    assertFalse(patternsMatcher.matches("wrong_pattern"));
  }

  @Test
  public void testHasPatterns() {
    PatternsMatcher patternsMatcher =
        new PatternsMatcher(Arrays.asList("pattern.*", "test_pattern"));

    assertTrue(patternsMatcher.hasPatterns());
  }

  @Test
  public void testHasNoPatterns() {
    PatternsMatcher patternsMatcher = new PatternsMatcher(Collections.emptyList());

    assertFalse(patternsMatcher.hasPatterns());
  }

  @Test
  public void testFilterMatchingMapEntriesWithEmptyPatterns() {
    PatternsMatcher patternsMatcher = new PatternsMatcher(Collections.emptyList());

    Map<String, String> entries =
        new TreeMap<String, String>() {
          {
            put("e1", "v1");
            put("e2", "v2");
            put("e3", "v3");
          }
        };

    assertEquals(entries, patternsMatcher.filterMatchingMapKeys(entries));
  }

  @Test
  public void testFilterMatchingMapEntries() {
    PatternsMatcher patternsMatcher = new PatternsMatcher(Arrays.asList("e1", "e2"));

    Map<String, String> entries =
        new TreeMap<String, String>() {
          {
            put("e1", "v1");
            put("e2", "v2");
            put("e3", "v3");
          }
        };

    Map<String, String> expectedEntries =
        new TreeMap<String, String>() {
          {
            put("e1", "v1");
            put("e2", "v2");
          }
        };

    assertEquals(expectedEntries, patternsMatcher.filterMatchingMapKeys(entries));
  }
}

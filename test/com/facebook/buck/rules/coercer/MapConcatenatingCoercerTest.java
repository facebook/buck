/*
 * Copyright 2018-present Facebook, Inc.
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

import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import org.junit.Test;

public class MapConcatenatingCoercerTest {
  @Test
  public void testConcatCanMergeMaps() {
    MapConcatenatingCoercer coercer = new MapConcatenatingCoercer();

    assertEquals(
        ImmutableMap.of("a", "a", "b", "b", "c", "c", "d", "d"),
        coercer.concat(
            Arrays.asList(
                ImmutableMap.of("a", "a"),
                ImmutableMap.of("b", "b", "c", "c"),
                ImmutableMap.of("d", "d"))));
  }
}

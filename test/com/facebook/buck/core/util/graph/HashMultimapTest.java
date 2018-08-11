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

package com.facebook.buck.core.util.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.HashMultimap;
import org.junit.Test;

public class HashMultimapTest {

  /**
   * The specification on {@link HashMultimap} is silent on whether {@link
   * HashMultimap#remove(Object, Object)} removes the key from the Multimap if the key/value pair is
   * the last entry for the key in the map. This test verifies this behavior as {@link
   * MutableDirectedGraph} depends on it.
   */
  @Test
  public void testRemoveLastEntryRemovesKey() {
    HashMultimap<String, String> edges = HashMultimap.create();
    edges.put("A", "B");
    edges.put("A", "C");
    edges.put("A", "D");
    assertEquals(3, edges.size());
    assertTrue(edges.containsKey("A"));

    edges.remove("A", "B");
    edges.remove("A", "C");
    edges.remove("A", "D");
    assertEquals(0, edges.size());
    assertFalse(edges.containsKey("A"));
  }
}

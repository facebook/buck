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

package net.starlark.java.eval;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Random;
import org.junit.Test;

public class DictMapTest {
  @Test
  public void capacityForSize() {
    assertEquals(0, DictMap.capacityForSize(0));
    assertEquals(16, DictMap.capacityForSize(1));
    assertEquals(16, DictMap.capacityForSize(2));
    assertEquals(16, DictMap.capacityForSize(7));
    assertEquals(16, DictMap.capacityForSize(8));
    assertEquals(32, DictMap.capacityForSize(9));
    assertEquals(32, DictMap.capacityForSize(10));
    assertEquals(32, DictMap.capacityForSize(15));
    assertEquals(32, DictMap.capacityForSize(16));
    assertEquals(64, DictMap.capacityForSize(17));
  }

  private final Random random = new Random(10);

  private int randomInt() {
    return random.nextInt(20);
  }

  private void getPutIter() {
    DictMap<Integer, Integer> map = new DictMap<>();
    LinkedHashMap<Integer, Integer> reference = new LinkedHashMap<>();
    for (int i = 0; i < 100; ++i) {
      int key = randomInt();
      assertEquals(reference.get(key), map.get(key));
      assertEquals(reference.size(), map.size());

      int value = randomInt();
      assertEquals(map.put(key, value), reference.put(key, value));
      map.doCheckSelf();
    }
  }

  @Test
  public void getPut() {
    for (int i = 0; i < 1000; ++i) {
      getPutIter();
    }
  }

  private void getPutRemoveIter() {
    DictMap<Integer, Integer> map = new DictMap<>();
    LinkedHashMap<Integer, Integer> reference = new LinkedHashMap<>();
    for (int i = 0; i < 100; ++i) {
      int key = randomInt();
      assertEquals(reference.get(key), map.get(key));
      assertEquals(reference.size(), map.size());

      if (random.nextBoolean()) {
        int value = randomInt();
        assertEquals(map.put(key, value), reference.put(key, value));
      } else {
        int key2 = random.nextInt();
        assertEquals(map.remove(key2), reference.remove(key2));
      }
      map.doCheckSelf();
    }
  }

  @Test
  public void getPutRemove() {
    for (int i = 0; i < 1000; ++i) {
      getPutRemoveIter();
    }
  }
}

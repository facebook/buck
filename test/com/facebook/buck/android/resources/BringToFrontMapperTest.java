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

package com.facebook.buck.android.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class BringToFrontMapperTest {
  @Test
  public void testIdentityMapping() {
    ReferenceMapper mapper =
        BringToFrontMapper.construct(0, ImmutableMap.of(0, ImmutableSortedSet.of(0, 1, 2)));

    assertEquals(0, mapper.map(0));
    assertEquals(1, mapper.map(1));
    assertEquals(2, mapper.map(2));
  }

  @Test
  public void testDifferentPackageId() {
    ReferenceMapper mapper =
        BringToFrontMapper.construct(0, ImmutableMap.of(0, ImmutableSortedSet.of(2, 0, 1)));

    // Values in a different package should be unchanged.
    assertEquals(0x0100_0000, mapper.map(0x0100_0000));
    assertEquals(0x0100_0001, mapper.map(0x0100_0001));
  }

  @Test
  public void testMapping() {
    ReferenceMapper mapper =
        BringToFrontMapper.construct(
            0,
            ImmutableMap.of(
                0, ImmutableSortedSet.of(0x4, 0x7, 0x10),
                1, ImmutableSortedSet.of(0x3, 0x5, 0x8)));

    assertEquals(0x00_0000, mapper.map(0x00_0004));
    assertEquals(0x00_0001, mapper.map(0x00_0007));
    assertEquals(0x00_0002, mapper.map(0x00_0010));

    assertEquals(0x01_0000, mapper.map(0x01_0003));
    assertEquals(0x01_0001, mapper.map(0x01_0005));
    assertEquals(0x01_0002, mapper.map(0x01_0008));
  }

  @Test
  public void testEmptyMapping() {
    ReferenceMapper mapper = BringToFrontMapper.construct(0, ImmutableMap.of());

    assertEquals(0x00_0000, mapper.map(0x00_0000));
    assertEquals(0x00_0001, mapper.map(0x00_0001));
    assertEquals(0x00_0002, mapper.map(0x00_0002));

    assertEquals(0x01_0000, mapper.map(0x01_0000));
    assertEquals(0x01_0001, mapper.map(0x01_0001));
    assertEquals(0x01_0002, mapper.map(0x01_0002));
  }

  @Test
  public void testMappingIsOneToOne() {
    ReferenceMapper mapper =
        BringToFrontMapper.construct(0, ImmutableMap.of(0, ImmutableSortedSet.of(0x4, 0x7, 0x10)));

    int max = 20;
    Set<Integer> seen = new HashSet<>();
    for (int i = 0; i < max; i++) {
      int mapped = mapper.map(i);
      assertTrue(mapped < max);
      assertTrue(mapped >= 0);
      assertFalse(seen.contains(mapped));
      seen.add(mapped);
    }
  }

  @Test
  public void testReordering() {
    int[] values = new int[] {100, 101, 102, 103, 104, 105, 106, 107};
    ReferenceMapper mapper =
        BringToFrontMapper.construct(0, ImmutableMap.of(0, ImmutableSortedSet.of(1, 4, 5)));

    IntBuffer buf = ByteBuffer.wrap(new byte[32]).asIntBuffer();
    Arrays.stream(values).forEach(buf::put);
    mapper.rewrite(0, buf);

    // First, confirm that the requested ones are in position.
    assertEquals(values[1], buf.get(0));
    assertEquals(values[4], buf.get(1));
    assertEquals(values[5], buf.get(2));

    // Check that the mapping matches the reordering.
    for (int i = 0; i < values.length; i++) {
      assertEquals(values[i], buf.get(mapper.map(i)));
    }
  }

  @Test
  public void testEmptyReordering() {
    int[] values = new int[] {100, 101, 102, 103, 104, 105, 106, 107};
    ReferenceMapper mapper = BringToFrontMapper.construct(0, ImmutableMap.of());

    IntBuffer buf = ByteBuffer.wrap(new byte[32]).asIntBuffer();
    Arrays.stream(values).forEach(buf::put);
    mapper.rewrite(0, buf);

    // First, verify that a few are unchanged.
    assertEquals(values[0], buf.get(0));
    assertEquals(values[1], buf.get(1));
    assertEquals(values[2], buf.get(2));

    // Check that the mapping matches the reordering.
    for (int i = 0; i < values.length; i++) {
      assertEquals(values[i], buf.get(mapper.map(i)));
    }
  }
}

/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GidGeneratorTest {
  @Test
  public void testTrivialGidGeneration() {
    GidGenerator generator = new GidGenerator(0);
    String gid = generator.generateGid();
    assertFalse("GID should be non-empty", gid.isEmpty());
    assertTrue("GID should be hexadecimal", gid.matches("^[0-9A-F]+$"));
    assertSame("GID should be 96 bits (24 hex digits)", gid.length(), 24);
  }

  @Test
  public void testStableGidGeneration() {
    GidGenerator generator1 = new GidGenerator(1);
    GidGenerator generator2 = new GidGenerator(2);

    String gid1 = generator1.generateStableGid("bla", 0);
    String gid1bis = generator1.generateStableGid("bla", 0);
    String gid2 = generator2.generateStableGid("bla", 0);

    assertFalse("GID should be non-empty", gid1.isEmpty());
    assertTrue("GID should be hexadecimal", gid1.matches("^[0-9A-F]+$"));
    assertSame("GID should be 96 bits (24 hex digits)", gid1.length(), 24);

    assertEquals("Two runs of the stable GID should yield the same result", gid1, gid2);
    assertNotEquals("The GID generator should avoid collisions", gid1, gid1bis);
  }

}

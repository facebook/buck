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

package com.facebook.buck.util.collect;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HashtablesTest {

  @Test
  public void tableSizeForCollectionSize() {
    assertEquals(0, Hashtables.tableSizeForCollectionSize(0));
    assertEquals(1, Hashtables.tableSizeForCollectionSize(1));
    assertEquals(2, Hashtables.tableSizeForCollectionSize(2));
    assertEquals(4, Hashtables.tableSizeForCollectionSize(3));
    assertEquals(8, Hashtables.tableSizeForCollectionSize(4));
    assertEquals(8, Hashtables.tableSizeForCollectionSize(5));
    assertEquals(8, Hashtables.tableSizeForCollectionSize(6));
    assertEquals(16, Hashtables.tableSizeForCollectionSize(8));
    assertEquals(16, Hashtables.tableSizeForCollectionSize(9));
    assertEquals(16, Hashtables.tableSizeForCollectionSize(10));
    assertEquals(16, Hashtables.tableSizeForCollectionSize(11));
    assertEquals(16, Hashtables.tableSizeForCollectionSize(12));
  }
}

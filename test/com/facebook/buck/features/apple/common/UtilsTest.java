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

package com.facebook.buck.features.apple.common;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;

public class UtilsTest {

  @Test
  public void testDistinctUntilChanged() {
    List<Integer> list = ImmutableList.of(1, 2, 3, 3, 4, 3, 4, 5);
    Iterable<Integer> filteredList = Utils.distinctUntilChanged(list);
    assertEquals(ImmutableList.of(1, 2, 3, 4, 3, 4, 5), filteredList);
  }
}

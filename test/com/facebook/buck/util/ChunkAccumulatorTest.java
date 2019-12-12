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

package com.facebook.buck.util;

import static org.junit.Assert.assertThat;

import com.google.common.base.Charsets;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ChunkAccumulatorTest {

  @Test
  public void simple() {
    ChunkAccumulator accumulator = new ChunkAccumulator(Charsets.UTF_8, 100);
    accumulator.append("hello");
    accumulator.append("world");
    assertThat(accumulator.getChunks(), Matchers.contains("hello", "world"));
  }

  @Test
  public void overflow() {
    ChunkAccumulator accumulator = new ChunkAccumulator(Charsets.UTF_8, 8);
    accumulator.append("hello");
    accumulator.append("world");
    assertThat(accumulator.getChunks(), Matchers.contains("world"));
  }

  @Test
  public void bigOverflow() {
    ChunkAccumulator accumulator = new ChunkAccumulator(Charsets.UTF_8, 10);
    accumulator.append("hello");
    accumulator.append("world");
    accumulator.append("big chunk");
    assertThat(accumulator.getChunks(), Matchers.contains("big chunk"));
  }

  @Test
  public void chunkTooBigForAccumulator() {
    ChunkAccumulator accumulator = new ChunkAccumulator(Charsets.UTF_8, 10);
    accumulator.append("super big chunk");
    assertThat(accumulator.getChunks(), Matchers.empty());
  }
}

/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Accumulates {@link String} chunks, keeping only the most recent to stay under a given maximum
 * size.
 */
public class ChunkAccumulator {

  private final Charset charset;
  private final long maxSize;

  private final Queue<String> chunks = new LinkedList<>();
  private long size = 0;

  public ChunkAccumulator(Charset charset, long maxSize) {
    this.charset = charset;
    this.maxSize = maxSize;
  }

  private void pop() {
    Preconditions.checkState(!chunks.isEmpty());
    String chunk = chunks.remove();
    size -= chunk.getBytes(charset).length;
  }

  private void push(String chunk) {
    Preconditions.checkState(chunk.getBytes(charset).length <= available());
    chunks.add(chunk);
    size += chunk.getBytes(charset).length;
  }

  private long available() {
    return maxSize - size;
  }

  public void append(String chunk) {
    long chunkSize = chunk.getBytes(charset).length;
    while (chunkSize > available() && !chunks.isEmpty()) {
      pop();
    }
    if (chunkSize <= available()) {
      push(chunk);
    }
  }

  public ImmutableList<String> getChunks() {
    return ImmutableList.copyOf(chunks);
  }

}

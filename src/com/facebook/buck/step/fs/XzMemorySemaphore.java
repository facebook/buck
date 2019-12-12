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

package com.facebook.buck.step.fs;

import java.util.concurrent.Semaphore;

public class XzMemorySemaphore {
  private static final int[] XZ_MEMORY_USAGE_MB = {1, 1, 1, 5, 10, 20, 40, 85, 175, 600};
  private static final int MAX_MEMORY = (int) (Runtime.getRuntime().maxMemory() / 2 / 1024 / 1024);
  private static final Semaphore memorySemaphore = new Semaphore(MAX_MEMORY);

  private XzMemorySemaphore() {}

  static void acquireMemory(int xzCompressionLevel) {
    try {
      memorySemaphore.acquire(XZ_MEMORY_USAGE_MB[xzCompressionLevel]);
    } catch (InterruptedException exp) {
      // ignore it
    }
  }

  static void releaseMemory(int xzCompressionLevel) {
    memorySemaphore.release(XZ_MEMORY_USAGE_MB[xzCompressionLevel]);
  }
}

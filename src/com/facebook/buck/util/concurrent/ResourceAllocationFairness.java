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

package com.facebook.buck.util.concurrent;

/** How to handle fairness when acquiring the semaphore. */
public enum ResourceAllocationFairness {
  /**
   * Make acquisitions happen in order. This may mean a high-permit count acquisition can block
   * smaller ones queued behind it that could otherwise grab the semaphore.
   */
  FAIR,
  /**
   * Move lower-permit count acquisitions that can acquire the lock ahead of high-count ones that
   * are blocked due to size.
   */
  FAST,
}

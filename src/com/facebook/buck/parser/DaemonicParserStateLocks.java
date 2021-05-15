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

package com.facebook.buck.parser;

import com.facebook.buck.util.concurrent.AutoCloseableReadLocked;
import com.facebook.buck.util.concurrent.AutoCloseableReadWriteLock;
import com.facebook.buck.util.concurrent.AutoCloseableWriteLocked;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/** Lock objects shared by different state objects. */
class DaemonicParserStateLocks {
  /** Locks cache data. */
  final AutoCloseableReadWriteLock cachesLock = new AutoCloseableReadWriteLock();

  /**
   * Cache validation model is following:
   *
   * <ul>
   *   <li>We acquire write lock to update invalidation count
   *   <li>We acquire read lock to update the cache, so invalidation count cannot be updated while
   *       we performing cache write
   *   <li>We don't acquire any locks when we read from the cache, but we check the invalidation
   *       number after we perform cache read
   * </ul>
   */
  private final AtomicInteger invalidationCount = new AtomicInteger();

  /**
   * Increment invalidation counter.
   *
   * <p>Note, invalidation counter increment <b>must</b> happen before updating cache fields because
   * we read cache without locking, so cache read must not successfully observe partially invalidate
   * cache.
   */
  void markInvalidated(AutoCloseableWriteLocked locked) {
    locked.markUsed();

    // This is slightly inefficient: we don't need to increment counter multiple times
    // per invalidation, but that is likely non-issue.
    invalidationCount.incrementAndGet();
  }

  boolean isValid(DaemonicParserValidationToken token) {
    return token.invalidationCount == invalidationCount.get();
  }

  /** Lock cache for update, return {@code null} token is not valid. */
  @Nullable
  AutoCloseableReadLocked lockForUpdate(DaemonicParserValidationToken validationToken) {
    AutoCloseableReadLocked locked = cachesLock.lockRead();
    // Must hold lock to check if token is valid.
    if (!isValid(validationToken)) {
      locked.close();
      return null;
    }
    return locked;
  }

  DaemonicParserValidationToken validationToken() {
    // We must acquire a lock for reading `invalidationCount` because
    // invalidation and counter increment does not happen atomically.
    try (AutoCloseableReadLocked locked = cachesLock.lockRead()) {
      return new DaemonicParserValidationToken(invalidationCount.get());
    }
  }
}

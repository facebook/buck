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

import com.googlecode.concurentlocks.ReadWriteUpdateLock;
import com.googlecode.concurentlocks.ReentrantReadWriteUpdateLock;

public class AutoCloseableReadWriteUpdateLock implements ReadWriteUpdateLock {

  private final ReentrantReadWriteUpdateLock reentrantReadWriteUpdateLock;

  public AutoCloseableReadWriteUpdateLock() {
    reentrantReadWriteUpdateLock = new ReentrantReadWriteUpdateLock();
  }

  @Override
  public AutoCloseableLock readLock() {
    return AutoCloseableLock.createFor(reentrantReadWriteUpdateLock.readLock());
  }

  @Override
  public AutoCloseableLock updateLock() {
    return AutoCloseableLock.createFor(reentrantReadWriteUpdateLock.updateLock());
  }

  @Override
  public AutoCloseableLock writeLock() {
    return AutoCloseableLock.createFor(reentrantReadWriteUpdateLock.writeLock());
  }
}

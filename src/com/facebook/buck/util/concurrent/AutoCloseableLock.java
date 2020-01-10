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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

public class AutoCloseableLock implements AutoCloseable, Lock {
  private final Lock lock;

  private AutoCloseableLock(Lock lock) {
    this.lock = lock;
  }

  public static AutoCloseableLock createFor(Lock lock) {
    AutoCloseableLock l = new AutoCloseableLock(lock);
    l.lock();
    return l;
  }

  @Override
  public void close() {
    unlock();
  }

  @Override
  public void lock() {
    lock.lock();
  }

  @Override
  public void lockInterruptibly() throws InterruptedException {
    lock.lockInterruptibly();
  }

  @Override
  public boolean tryLock() {
    return lock.tryLock();
  }

  @Override
  public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
    return lock.tryLock(time, unit);
  }

  @Override
  public void unlock() {
    lock.unlock();
  }

  @Override
  public Condition newCondition() {
    return lock.newCondition();
  }
}

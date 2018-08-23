/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.util.shutdown;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * If a shutdown hook causes an unhandled exception and the unhandled exception handler calls {@link
 * System#exit} we end up deadlocking. This exists solely to prevent that scenario.
 */
public class NonReentrantSystemExit {

  private final AtomicBoolean shutdownInitiated;
  private final AtomicInteger exitCode;
  private final CountDownLatch doExitLatch;

  public NonReentrantSystemExit() {
    this.shutdownInitiated = new AtomicBoolean(false);
    this.exitCode = new AtomicInteger(-1);
    this.doExitLatch = new CountDownLatch(1);
    Thread thread =
        new Thread(NonReentrantSystemExit.class.getSimpleName()) {
          @Override
          public void run() {
            try {
              doExitLatch.await();
            } catch (InterruptedException e) {
              // This is one of the rare cases where it's OK to ignore InterruptedException:
              // - nobody should be joining on this thread, so it's not like the user will have
              //   to wait longer or anything,
              // - the shutdown hook depends on this thread running, exiting early will actually
              //   break functionality.
            }
            System.exit(exitCode.get());
          }
        };
    thread.start();
  }

  public void shutdownSoon(int exitCode) {
    if (!shutdownInitiated.compareAndSet(false, true)) {
      return;
    }
    this.exitCode.set(exitCode);
    doExitLatch.countDown();
  }
}

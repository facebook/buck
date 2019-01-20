/*
 * Copyright 2013-present Facebook, Inc.
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

package com.example;

import java.util.concurrent.ExecutorService;

/**
 * Here is a utility that exercises the problematic behavior in {@code
 * org.robolectric.shadows.ShadowLooper} where a reference to the thread that was used to load the
 * class is stored and assumed to be the main thread. Because there is a check that asserts that the
 * current thread (i.e., the thread on which the test is run) is the "main thread" (which again, is
 * assumed to be the thread that was used to load the class), that means that we cannot set up the
 * test runner on one thread and then run the tests on different threads when using the {@code
 * ShadowLooper} in Robolectric.
 *
 * <p>This is an issue because our {@link DelegateRunnerWithTimeout} does not run tests on the main
 * thread: it uses a {@link ExecutorService} that can be shutdown if a test exceeds its timeout. So
 * long as everything happens on the {@link ExecutorService}'s single thread, everything should be
 * fine.
 */
class UtilityThatHoldsAReferenceToTheMainThread {

  private static final Thread MAIN_THREAD = Thread.currentThread();

  public static synchronized void resetThreadLoopers() {
    if (Thread.currentThread() != MAIN_THREAD) {
      throw new RuntimeException("This is expected to be called from the main thread.");
    }
  }
}

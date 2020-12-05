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

package com.facebook.buck.testutil;

import com.facebook.buck.core.util.log.Logger;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ExecutorServiceUtils {

  private static final Logger LOG = Logger.get(ExecutorServiceUtils.class);

  private static final int DEFAULT_MAX_ATTEMPTS = 10;

  private static final long SLEEP_BETWEEN_ATTEMPTS_VALUE = 100L;
  private static final TimeUnit SLEEP_BETWEEN_ATTEMPTS_VALUE_UNIT = TimeUnit.MILLISECONDS;

  private ExecutorServiceUtils() {}

  public static void waitTillAllTasksCompleted(ThreadPoolExecutor threadPoolExecutor)
      throws InterruptedException {
    waitTillAllTasksCompleted(
        threadPoolExecutor,
        DEFAULT_MAX_ATTEMPTS,
        SLEEP_BETWEEN_ATTEMPTS_VALUE_UNIT,
        SLEEP_BETWEEN_ATTEMPTS_VALUE);
  }

  public static void waitTillAllTasksCompleted(
      ThreadPoolExecutor threadPoolExecutor,
      int maxAttempts,
      TimeUnit sleepBetweenAttemptsUnit,
      long sleepBetweenAttemptsValue)
      throws InterruptedException {
    int attempt = 0;
    while (threadPoolExecutor.getActiveCount() > 0 && attempt++ < maxAttempts) {
      sleepBetweenAttemptsUnit.sleep(sleepBetweenAttemptsValue);
      LOG.info(
          "Waiting till thread pool %s process all tasks. Attempt: # %s",
          threadPoolExecutor, attempt);
    }
  }
}

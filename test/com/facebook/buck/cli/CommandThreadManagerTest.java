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

package com.facebook.buck.cli;

import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.ResourceAllocationFairness;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.ResourceAmountsEstimator;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CommandThreadManagerTest {

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  @SuppressWarnings("PMD.EmptyWhileStmt")
  public void throwsOnHang() throws InterruptedException {
    exception.expect(RuntimeException.class);
    exception.expectMessage("Shutdown timed out for thread pool Test");
    exception.expectMessage("Thread Test-0");
    exception.expectMessage(this.getClass().getName());

    ConcurrencyLimit concurrencyLimit =
        new ConcurrencyLimit(
            /* threadLimit */ 1,
            ResourceAllocationFairness.FAIR,
            /* managedThreadCount */ 1,
            ResourceAmountsEstimator.DEFAULT_AMOUNTS,
            ResourceAmounts.of(
                1,
                ResourceAmountsEstimator.DEFAULT_MAXIMUM_AMOUNTS.getMemory(),
                ResourceAmountsEstimator.DEFAULT_MAXIMUM_AMOUNTS.getDiskIO(),
                ResourceAmountsEstimator.DEFAULT_MAXIMUM_AMOUNTS.getNetworkIO()));

    try (CommandThreadManager pool =
        new CommandThreadManager("Test", concurrencyLimit, 250, TimeUnit.MILLISECONDS)) {
      pool.getListeningExecutorService()
          .submit(
              () -> {
                while (true) {}
              });
    }
  }
}

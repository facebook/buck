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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

/** javacd configuration */
@BuckStyleValue
public abstract class JavaCDBuckConfig implements ConfigView<BuckConfig> {

  private static final String SECTION = "javacd";

  private static final int DEFAULT_WORKER_TOOL_POOL_SIZE = 1;
  private static final int DEFAULT_MAX_INSTANCES_PER_WORKER_VALUE = 64;

  private static final int DEFAULT_BORROW_FROM_THE_POOL_TIMEOUT_IN_SECONDS = 10 * 60;
  private static final int DEFAULT_MAX_WAIT_FOR_RESULT_TIMEOUT_IN_SECONDS = 10 * 60;

  @Override
  public abstract BuckConfig getDelegate();

  public static JavaCDBuckConfig of(BuckConfig delegate) {
    return ImmutableJavaCDBuckConfig.ofImpl(delegate);
  }

  /** Returns jvm flags that would be used to launch javacd. */
  @Value.Lazy
  public ImmutableList<String> getJvmFlags() {
    return getDelegate().getListWithoutComments(SECTION, "jvm_args");
  }

  /** Returns worker tool pool size. */
  @Value.Lazy
  public int getWorkerToolSize() {
    String propertyName = "worker_pool_size";
    int workerPoolSize =
        getDelegate().getInteger(SECTION, propertyName).orElse(DEFAULT_WORKER_TOOL_POOL_SIZE);
    verifyNonNegative(workerPoolSize, propertyName);
    return workerPoolSize;
  }

  /** Returns worker tool max instances size. */
  @Value.Lazy
  public int getWorkerToolMaxInstancesSize() {
    String propertyName = "worker_max_instances";
    int workerMaxInstances =
        getDelegate()
            .getInteger(SECTION, propertyName)
            .orElse(DEFAULT_MAX_INSTANCES_PER_WORKER_VALUE);
    verifyPositive(workerMaxInstances, propertyName);
    return workerMaxInstances;
  }

  /**
   * Returns the maximum number of seconds for waiting for an available worker from the worker tool
   * pool.
   */
  @Value.Lazy
  public int getBorrowFromPoolTimeoutInSeconds() {
    String propertyName = "borrow_from_the_pool_timeout_sec";
    int borrowFromThePoolTimeoutSec =
        getDelegate()
            .getInteger(SECTION, propertyName)
            .orElse(DEFAULT_BORROW_FROM_THE_POOL_TIMEOUT_IN_SECONDS);
    verifyPositive(borrowFromThePoolTimeoutSec, propertyName);
    return borrowFromThePoolTimeoutSec;
  }

  /** Returns the maximum number of seconds for waiting for a result from the worker tool. */
  @Value.Lazy
  public int getMaxWaitForResultTimeoutInSeconds() {
    String propertyName = "max_wait_for_result_timeout_sec";
    int maxWaitForResultTimeoutSec =
        getDelegate()
            .getInteger(SECTION, propertyName)
            .orElse(DEFAULT_MAX_WAIT_FOR_RESULT_TIMEOUT_IN_SECONDS);
    verifyPositive(maxWaitForResultTimeoutSec, propertyName);
    return maxWaitForResultTimeoutSec;
  }

  private void verifyNonNegative(int value, String propertyName) {
    Preconditions.checkState(value >= 0, propertyName + " has to be non negative");
  }

  private void verifyPositive(int value, String propertyName) {
    Preconditions.checkState(value > 0, propertyName + " has to be positive");
  }
}

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
import com.google.common.collect.ImmutableList;
import java.nio.charset.StandardCharsets;
import org.immutables.value.Value;

/** javacd configuration */
@BuckStyleValue
public abstract class JavaCDBuckConfig implements ConfigView<BuckConfig> {

  private static final String SECTION = "javacd";

  private static final int DEFAULT_WORKER_TOOL_POOL_SIZE =
      (int) Math.ceil(Runtime.getRuntime().availableProcessors() * 0.75);

  private static final int DEFAULT_BORROW_FROM_THE_POOL_TIMEOUT_IN_SECONDS = 30 * 60;

  @Override
  public abstract BuckConfig getDelegate();

  public static JavaCDBuckConfig of(BuckConfig delegate) {
    return ImmutableJavaCDBuckConfig.ofImpl(delegate);
  }

  /** Returns jvm flags that would be used to launch javacd. */
  @Value.Lazy
  public ImmutableList<String> getJvmFlags() {
    ImmutableList<String> args = getDelegate().getListWithoutComments(SECTION, "jvm_args");
    if (args.isEmpty()) {
      return ImmutableList.of("-Dfile.encoding=" + StandardCharsets.UTF_8.name());
    }
    return args;
  }

  /** Returns worker tool pool size. */
  @Value.Lazy
  public int getWorkerToolSize() {
    return getDelegate()
        .getInteger(SECTION, "worker_pool_size")
        .orElse(DEFAULT_WORKER_TOOL_POOL_SIZE);
  }

  /**
   * Returns the maximum number of seconds for waiting for an available worker from the worker tool
   * pool.
   */
  @Value.Lazy
  public int getBorrowFromPoolTimeoutInSeconds() {
    return getDelegate()
        .getInteger(SECTION, "borrow_from_the_pool_timeout_sec")
        .orElse(DEFAULT_BORROW_FROM_THE_POOL_TIMEOUT_IN_SECONDS);
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.util.Console;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.function.Supplier;

/** Default implementation of ADB execution context used in buck1 */
public class DefaultAdbExecutionContext implements AdbExecutionContext {

  private final Supplier<ExecutionContext> contextSupplier;

  public DefaultAdbExecutionContext(Supplier<ExecutionContext> contextSupplier) {
    this.contextSupplier = contextSupplier;
  }

  @Override
  public Optional<BuckEventBus> getBuckEventBus() {
    return Optional.of(contextSupplier.get().getBuckEventBus());
  }

  @Override
  public ImmutableMap<String, String> getEnvironment() {
    return contextSupplier.get().getEnvironment();
  }

  @Override
  public Console getConsole() {
    return contextSupplier.get().getConsole();
  }
}

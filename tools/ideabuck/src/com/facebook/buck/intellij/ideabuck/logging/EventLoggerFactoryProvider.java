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

package com.facebook.buck.intellij.ideabuck.logging;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.extensions.ExtensionPointName;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class EventLoggerFactoryProvider {

  private static class EventLoggerFactoryProviderHolder {
    static final EventLoggerFactoryProvider INSTANCE = new EventLoggerFactoryProvider();
  }

  public static EventLoggerFactoryProvider getInstance() {
    return EventLoggerFactoryProviderHolder.INSTANCE;
  }

  private final Supplier<List<EventLoggerFactory>> mFactoriesSupplier;

  static final ExtensionPointName<EventLoggerFactory> EP_NAME =
      ExtensionPointName.create("intellij.buck.plugin.EventLoggerFactory");

  private EventLoggerFactoryProvider() {
    this(EP_NAME::getExtensionList);
  }

  @VisibleForTesting
  EventLoggerFactoryProvider(Supplier<List<EventLoggerFactory>> factoriesSupplier) {
    mFactoriesSupplier = factoriesSupplier;
  }

  public BuckEventLogger getBuckEventLogger(String eventType) {
    return new BuckEventLogger(getEventLoggers(eventType));
  }

  @VisibleForTesting
  BuckEventLogger getBuckEventLogger(String eventType, Executor executor) {
    return new BuckEventLogger(getEventLoggers(eventType), executor);
  }

  private List<EventLogger> getEventLoggers(String eventType) {
    return mFactoriesSupplier.get().stream()
        .map(factory -> factory.eventLogger(eventType))
        .collect(Collectors.toList());
  }
}

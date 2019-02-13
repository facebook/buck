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
package com.facebook.buck.event.listener;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.event.external.events.BuckEventExternalInterface;
import org.immutables.value.Value;

/**
 * Proxy events are used by the logging system when we need to slice a {@link
 * com.facebook.buck.event.BuckEvent} in half in order to calculate time elapsed.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractProxyBuckEvent implements BuckEventExternalInterface {
  @Value.Parameter
  @Override
  public abstract long getTimestampMillis();

  @Override
  public String getEventName() {
    return "Proxy BuckEvent";
  }
}

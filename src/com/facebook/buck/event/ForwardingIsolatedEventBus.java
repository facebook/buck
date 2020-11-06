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

package com.facebook.buck.event;

import com.facebook.buck.core.model.BuildId;
import java.io.IOException;
import java.time.Instant;

/** An {@link IsolatedEventBus} that forwards events to a {@link BuckEventBus}. */
public class ForwardingIsolatedEventBus implements IsolatedEventBus {

  private final BuckEventBus buckEventBus;

  public ForwardingIsolatedEventBus(BuckEventBus buckEventBus) {
    this.buckEventBus = buckEventBus;
  }

  @Override
  public BuildId getBuildId() {
    return buckEventBus.getBuildId();
  }

  @Override
  public void post(ConsoleEvent event) {
    buckEventBus.post(event);
  }

  @Override
  public void post(ConsoleEvent event, long threadId) {
    buckEventBus.post(event, threadId);
  }

  @Override
  public void post(ExternalEvent event) {
    buckEventBus.post(event);
  }

  @Override
  public void post(ExternalEvent event, long threadId) {
    buckEventBus.post(event, threadId);
  }

  @Override
  public void post(StepEvent event) {
    buckEventBus.post(event);
  }

  @Override
  public void post(StepEvent event, long threadId) {
    buckEventBus.post(event, threadId);
  }

  @Override
  public void post(StepEvent event, Instant atTime, long threadId) {
    buckEventBus.post(event, atTime, threadId);
  }

  @Override
  public void post(SimplePerfEvent event) {
    buckEventBus.post(event);
  }

  @Override
  public void post(SimplePerfEvent event, Instant atTime) {
    buckEventBus.post(event, atTime);
  }

  @Override
  public void post(SimplePerfEvent event, long threadId) {
    buckEventBus.post(event, threadId);
  }

  @Override
  public void post(SimplePerfEvent event, Instant atTime, long threadId) {
    buckEventBus.post(event, atTime, threadId);
  }

  @Override
  public void postWithoutConfiguring(SimplePerfEvent event) {
    buckEventBus.postWithoutConfiguring(event);
  }

  @Override
  public void timestamp(ConsoleEvent event) {
    buckEventBus.timestamp(event);
  }

  @Override
  public void timestamp(StepEvent event) {
    buckEventBus.timestamp(event);
  }

  @Override
  public void timestamp(SimplePerfEvent event) {
    buckEventBus.timestamp(event);
  }

  @Override
  public void timestamp(SimplePerfEvent event, long threadId) {
    buckEventBus.timestamp(event, threadId);
  }

  @Override
  public void close() throws IOException {
    buckEventBus.close();
  }
}

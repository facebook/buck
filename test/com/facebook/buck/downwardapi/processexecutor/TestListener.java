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

package com.facebook.buck.downwardapi.processexecutor;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.google.common.eventbus.Subscribe;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class TestListener {

  private final AtomicInteger counter = new AtomicInteger(-1);
  private final Map<Integer, BuckEvent> events = new HashMap<>();

  @Subscribe
  public void console(com.facebook.buck.event.ConsoleEvent event) {
    handleEvent(event);
  }

  @Subscribe
  public void chromeTrace(SimplePerfEvent event) {
    handleEvent(event);
  }

  @Subscribe
  public void step(com.facebook.buck.event.StepEvent event) {
    handleEvent(event);
  }

  @Subscribe
  public void external(com.facebook.buck.event.ExternalEvent event) {
    handleEvent(event);
  }

  private void handleEvent(BuckEvent event) {
    events.put(counter.incrementAndGet(), event);
  }

  public Map<Integer, BuckEvent> getEvents() {
    return events;
  }
}

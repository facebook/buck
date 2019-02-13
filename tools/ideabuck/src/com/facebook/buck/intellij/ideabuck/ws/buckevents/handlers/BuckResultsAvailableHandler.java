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

package com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers;

import com.facebook.buck.event.external.events.BuckEventExternalInterface;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckEventsConsumerFactory;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.TestResultsAvailableConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.parts.TestResultsAvailable;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

public class BuckResultsAvailableHandler implements BuckEventHandler {
  @Override
  public void handleEvent(
      String rawMessage,
      BuckEventExternalInterface event,
      BuckEventsConsumerFactory buckEventsConsumerFactory,
      ObjectMapper objectMapper)
      throws IOException {
    TestResultsAvailable testResultsAvailable =
        objectMapper.readValue(rawMessage, TestResultsAvailable.class);

    testResultsAvailable.getResults();
    TestResultsAvailableConsumer consumer =
        buckEventsConsumerFactory.getTestResultsAvailableConsumer();
    consumer.consumeTestResultsAvailable(
        event.getTimestampMillis(), testResultsAvailable.getResults());
  }
}

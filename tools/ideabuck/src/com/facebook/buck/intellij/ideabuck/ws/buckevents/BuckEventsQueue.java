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

package com.facebook.buck.intellij.ideabuck.ws.buckevents;

import com.facebook.buck.event.external.events.BuckEventExternalInterface;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckEventsConsumerFactory;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BuckEventsQueue implements BuckEventsQueueInterface {

  private static final Logger LOG = Logger.getInstance(BuckEventsQueue.class);

  private final BuckEventsConsumerFactory mFactory;
  private final ExecutorService mSingleThreadExecutor;
  private ObjectMapper mObjectMapper;
  private BuckEventsAdapter mBuckEventsAdapter;

  public BuckEventsQueue(ObjectMapper objectMapper, BuckEventsConsumerFactory factory) {
    mFactory = factory;
    mObjectMapper = objectMapper;
    mBuckEventsAdapter = new BuckEventsAdapter();
    mSingleThreadExecutor = Executors.newSingleThreadExecutor();
  }

  @Override
  public void add(String rawMessage, BuckEventExternalInterface event) {
    handleEvent(rawMessage, event);
  }

  private void handleEvent(final String rawMessage, final BuckEventExternalInterface event) {
    mSingleThreadExecutor.submit(
        () -> {
          try {
            String eventName = event.getEventName();
            BuckEventHandler buckEventHandler = mBuckEventsAdapter.get(eventName);
            if (buckEventHandler == null) {
              // Log the first time, then ignore to avoid spamming logs with unhandled events
              LOG.warn("Unhandled event '" + eventName + "': " + rawMessage);
              mBuckEventsAdapter.put(eventName, BuckEventHandler.IGNORE);
            } else {
              buckEventHandler.handleEvent(rawMessage, event, mFactory, mObjectMapper);
            }
          } catch (IOException e) {
            e.printStackTrace();
          }
        });
  }
}

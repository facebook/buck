/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.ws.buckevents;

import com.facebook.buck.event.external.events.BuckEventExternalInterface;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckEventsConsumerFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.module.mrbean.MrBeanModule;
import java.io.IOException;

public class BuckEventHandler implements BuckEventsHandlerInterface {
  private final BuckEventsQueueInterface mQueue;
  private final ObjectMapper mObjectMapper;

  private Runnable mOnConnectHandler = null;
  private Runnable mOnDisconnectHandler = null;

  public BuckEventHandler(
      BuckEventsConsumerFactory consumerFactory,
      Runnable onConnectHandler,
      Runnable onDisconnectHandler) {
    mOnConnectHandler = onConnectHandler;
    mOnDisconnectHandler = onDisconnectHandler;

    mObjectMapper = new ObjectMapper();
    mObjectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    mObjectMapper.registerModule(new MrBeanModule());
    mObjectMapper.registerModule(new GuavaModule());

    mQueue = new BuckEventsQueue(mObjectMapper, consumerFactory);
  }

  @Override
  public void onConnect() {
    if (mOnConnectHandler != null) {
      mOnConnectHandler.run();
    }
  }

  @Override
  public void onDisconnect() {
    if (mOnDisconnectHandler != null) {
      mOnDisconnectHandler.run();
    }
  }

  @Override
  public void onMessage(final String message) {
    final BuckEventExternalInterface buckEventExternalInterface;
    try {
      buckEventExternalInterface =
          mObjectMapper.readValue(message, BuckEventExternalInterface.class);
      mQueue.add(message, buckEventExternalInterface);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}

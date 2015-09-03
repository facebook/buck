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

package com.facebook.buck.event.listener;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.event.HttpArtifactCacheEvent;
import com.facebook.buck.event.HttpArtifactCacheEvent.Finished;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.network.RemoteLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.ListenableFuture;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

public class HttpArtifactCacheEventListenerTest {

  private static final BuildId BUILD_ID = new BuildId("My Super ID");
  private static final ObjectMapper CONVERTER = new ObjectMapper();

  private RemoteLogger logger;
  private HttpArtifactCacheListener listener;

  @Before
  public void setUp() {
    logger = EasyMock.createMock(RemoteLogger.class);
    listener = new HttpArtifactCacheListener(logger, CONVERTER);
  }

  @Test
  public void creatingRowWithoutColumns() throws InterruptedException {
    Capture<String> logLineCapture = Capture.newInstance();
    EasyMock.expect(
        logger.log(
            EasyMock.capture(logLineCapture)))
        .andReturn(Optional.<ListenableFuture<Void>>absent())
        .once();
    EasyMock.expect(logger.close())
        .andReturn(null)
        .once();
    EasyMock.replay(logger);

    String errorMsg = "My super cool error message!!!";

    HttpArtifactCacheEvent.Started startedEvent = HttpArtifactCacheEvent.newFetchStartedEvent();
    startedEvent.configure(-1, -1, -1, null);
    Finished event = HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent)
        .setErrorMessage(errorMsg)
        .build();
    event.configure(-1, -1, -1, BUILD_ID);
    listener.onHttpArtifactCacheEvent(event);
    listener.outputTrace(BUILD_ID);
    EasyMock.verify(logger);
    String actualLogLine = logLineCapture.getValue();
    assertFalse(Strings.isNullOrEmpty(actualLogLine));
    assertTrue(actualLogLine.contains(errorMsg));
    assertTrue(actualLogLine.contains(BUILD_ID.toString()));
  }
}

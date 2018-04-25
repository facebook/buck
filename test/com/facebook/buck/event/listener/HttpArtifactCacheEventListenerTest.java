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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent.Finished;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.util.network.BatchingLogger;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.Futures;
import java.util.Optional;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

public class HttpArtifactCacheEventListenerTest {

  private static final BuildId BUILD_ID = new BuildId("My Super ID");

  private BatchingLogger storeLogger;
  private BatchingLogger fetchLogger;
  private HttpArtifactCacheEventListener listener;

  @Before
  public void setUp() {
    storeLogger = EasyMock.createMock(BatchingLogger.class);
    fetchLogger = EasyMock.createMock(BatchingLogger.class);
    listener = new HttpArtifactCacheEventListener(storeLogger, fetchLogger);
  }

  @Test
  public void creatingRowWithoutColumns() {
    Capture<String> logLineCapture = Capture.newInstance();
    EasyMock.expect(fetchLogger.log(EasyMock.capture(logLineCapture)))
        .andReturn(Optional.empty())
        .once();
    EasyMock.expect(fetchLogger.forceFlush()).andReturn(Futures.immediateFuture(null)).once();
    EasyMock.replay(fetchLogger);
    EasyMock.expect(storeLogger.forceFlush()).andReturn(Futures.immediateFuture(null)).once();
    EasyMock.replay(storeLogger);

    String errorMsg = "My super cool error message!!!";

    HttpArtifactCacheEvent.Started startedEvent =
        ArtifactCacheTestUtils.newFetchConfiguredStartedEvent(new RuleKey("1234"));
    Finished.Builder builder = HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent);
    builder
        .getFetchBuilder()
        .setFetchResult(CacheResult.hit("http", ArtifactCacheMode.http))
        .setErrorMessage(errorMsg);
    Finished event = builder.build();
    event.configure(-1, -1, -1, -1, BUILD_ID);
    listener.onHttpArtifactCacheEvent(event);
    listener.outputTrace(BUILD_ID);
    EasyMock.verify(fetchLogger);
    String actualLogLine = logLineCapture.getValue();
    assertFalse(Strings.isNullOrEmpty(actualLogLine));
    assertTrue(actualLogLine.contains(errorMsg));
    assertTrue(actualLogLine.contains(BUILD_ID.toString()));
  }
}

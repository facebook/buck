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

package com.facebook.buck.util.network;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.LogRequestType;
import com.facebook.buck.distributed.thrift.ScribeData;
import com.facebook.buck.slb.ThriftException;
import com.facebook.buck.slb.ThriftService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsIterableWithSize;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ThriftScribeLoggerTest {

  private static final String CATEGORY = "TEST_CATEGORY";
  private static final ImmutableList<String> LINES = ImmutableList.of("t1", "t2");

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private ListeningExecutorService executorService;
  private ThriftScribeLogger logger;
  private FrontendRequest request;

  @Before
  public void setUp() {
    logger = null;
    executorService = MoreExecutors.newDirectExecutorService();
  }

  @After
  public void tearDown() throws Exception {
    if (logger != null) {
      logger.close();
      logger = null;
    }
  }

  @Test
  public void testAllNullLinesAreRemoved() {
    ScribeData data = new ScribeData().setCategory("slicespin");
    assertEquals(0, data.getLinesSize());
    List<String> lines = Lists.newArrayList("topspin", null, "down", "the", "lines");
    ThriftScribeLogger.copyLinesWithoutNulls(lines, data);
    assertEquals(lines.size() - 1, data.getLinesSize());
  }

  @Test
  public void handlingThriftCallSucceeding() {
    logger = new ThriftScribeLogger(getNotThrowingThriftService(true), executorService);
    Futures.addCallback(logger.log(CATEGORY, LINES), getCallback(true));
  }

  @Test
  public void handlingThriftCallFailing() {
    logger = new ThriftScribeLogger(getNotThrowingThriftService(false), executorService);
    Futures.addCallback(logger.log(CATEGORY, LINES), getCallback(false));
  }

  @Test
  public void handlingThriftCallThrowing() {
    logger = new ThriftScribeLogger(getThrowingThriftService(), executorService);
    Futures.addCallback(logger.log(CATEGORY, LINES), getCallback(false));
  }

  @Test
  public void requestIsCorrectlyCreated() {
    ThriftService<FrontendRequest, FrontendResponse> thriftService = createDefaultListener();
    logger = new ThriftScribeLogger(thriftService, executorService);
    logger.log(CATEGORY, LINES);

    // Test request outside as otherwise an assertion could fail silently.
    assertEquals(request.getType(), FrontendRequestType.LOG);
    assertEquals(request.getLogRequest().getType(), LogRequestType.SCRIBE_DATA);
    assertEquals(request.getLogRequest().getScribeData().getCategory(), CATEGORY);
    assertThat(
        request.getLogRequest().getScribeData().getLines(),
        Matchers.allOf(
            hasItem(LINES.get(0)), hasItem(LINES.get(1)), IsIterableWithSize.iterableWithSize(2)));
  }

  private ThriftService<FrontendRequest, FrontendResponse> createDefaultListener() {
    return new ThriftService<FrontendRequest, FrontendResponse>() {
      @Override
      public void makeRequest(FrontendRequest frontendRequest, FrontendResponse frontendResponse) {
        request = frontendRequest;
      }

      @Override
      public void close() {}
    };
  }

  @Test
  public void allLogRequestsDoNotFailAfterClosingTheLogger()
      throws IOException, ExecutionException, InterruptedException {
    logger = new ThriftScribeLogger(createDefaultListener(), executorService);
    logger.close();
    ListenableFuture<Void> future = logger.log("topspin", Lists.newArrayList("down the line"));
    assertTrue(future.isDone());
    future.get();
  }

  private ThriftService<FrontendRequest, FrontendResponse> getNotThrowingThriftService(
      boolean wasSuccessful) {
    return new ThriftService<FrontendRequest, FrontendResponse>() {
      @Override
      public void makeRequest(FrontendRequest frontendRequest, FrontendResponse frontendResponse) {
        frontendResponse.setWasSuccessful(wasSuccessful);
      }

      @Override
      public void close() {}
    };
  }

  private ThriftService<FrontendRequest, FrontendResponse> getThrowingThriftService() {
    return new ThriftService<FrontendRequest, FrontendResponse>() {
      @Override
      public void makeRequest(FrontendRequest frontendRequest, FrontendResponse frontendResponse)
          throws ThriftException {
        throw new ThriftException("Error");
      }

      @Override
      public void close() {}
    };
  }

  private FutureCallback<Void> getCallback(boolean shouldSucceed) {
    return new FutureCallback<Void>() {
      @Override
      public void onSuccess(@Nullable Void result) {
        assertTrue(shouldSucceed);
      }

      @Override
      public void onFailure(Throwable t) {
        assertFalse(shouldSucceed);
      }
    };
  }
}

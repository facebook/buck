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

package com.facebook.buck.util.network.thrift;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.LogRequestType;
import com.facebook.buck.slb.ThriftService;
import com.facebook.buck.slb.ThriftServiceException;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.hamcrest.collection.IsIterableWithSize;
import org.junit.Test;

import java.io.IOException;

import javax.annotation.Nullable;

public class ThriftScribeLoggerTest {

  private static final String CATEGORY =  "TEST_CATEGORY";
  private static final ImmutableList<String> LINES = ImmutableList.of("t1", "t2");

  private static final ListeningExecutorService EXECUTOR_SERVICE =
      MoreExecutors.newDirectExecutorService();

  private FrontendRequest request;

  @Test
  public void handlingThriftCallSucceeding() throws Exception {
    ThriftScribeLogger logger =
        new ThriftScribeLogger(getNotThrowingThriftService(true), EXECUTOR_SERVICE);
    Futures.addCallback(logger.log(CATEGORY, LINES), getCallback(true));
    logger.close();
  }

  @Test
  public void handlingThriftCallFailing() throws Exception {
    ThriftScribeLogger logger =
        new ThriftScribeLogger(getNotThrowingThriftService(false), EXECUTOR_SERVICE);
    Futures.addCallback(logger.log(CATEGORY, LINES), getCallback(false));
    logger.close();
  }

  @Test
  public void handlingThriftCallThrowing() throws Exception {
    ThriftScribeLogger logger =
        new ThriftScribeLogger(getThrowingThriftService(), EXECUTOR_SERVICE);
    Futures.addCallback(logger.log(CATEGORY, LINES), getCallback(false));
    logger.close();
  }

  @Test
  public void requestIsCorrectlyCreated() throws Exception {
    ThriftService<FrontendRequest, FrontendResponse> thriftService =
        new ThriftService<FrontendRequest, FrontendResponse>() {
          @Override
          public void makeRequest(
              FrontendRequest frontendRequest,
              FrontendResponse frontendResponse) {
            request = frontendRequest;
          }

          @Override
          public void close() throws IOException {
          }
        };
    ThriftScribeLogger logger = new ThriftScribeLogger(thriftService, EXECUTOR_SERVICE);
    logger.log(CATEGORY, LINES);
    logger.close();

    // Test request outside as otherwise an assertion could fail silently.
    assertEquals(request.getType(), FrontendRequestType.LOG);
    assertEquals(request.getLogRequest().getType(), LogRequestType.SCRIBE_DATA);
    assertEquals(request.getLogRequest().getScribeData().getCategory(), CATEGORY);
    assertThat(request.getLogRequest().getScribeData().getLines(), Matchers.allOf(
        hasItem(LINES.get(0)),
        hasItem(LINES.get(1)),
        IsIterableWithSize.<String>iterableWithSize(2)
    ));
  }

  private ThriftService<FrontendRequest, FrontendResponse>
      getNotThrowingThriftService(final boolean wasSuccessful) {
    return new ThriftService<FrontendRequest, FrontendResponse>() {
      @Override
      public void makeRequest(
          FrontendRequest frontendRequest,
          FrontendResponse frontendResponse) {
        frontendResponse.setWasSuccessful(wasSuccessful);
      }

      @Override
      public void close() throws IOException {
      }
    };
  }

  private ThriftService<FrontendRequest, FrontendResponse> getThrowingThriftService() {
    return new ThriftService<FrontendRequest, FrontendResponse>() {
      @Override
      public void makeRequest(
          FrontendRequest frontendRequest,
          FrontendResponse frontendResponse) throws ThriftServiceException {
        throw new ThriftServiceException("Error");
      }

      @Override
      public void close() throws IOException {
      }
    };
  }

  private FutureCallback<Void> getCallback(final boolean shouldSucceed) {
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

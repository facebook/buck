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

package com.facebook.buck.logd.client;

import com.facebook.buck.core.util.log.Logger;
import com.google.rpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * A test implementation of StreamObserverFactory. Uses TestHelper to catch messages and/or errors
 * in streaming.
 */
public class TestStreamObserverFactory implements StreamObserverFactory {
  private static final Logger LOG = Logger.get(TestStreamObserverFactory.class.getName());
  private TestHelper testHelper;

  public TestStreamObserverFactory(TestHelper testHelper) {
    this.testHelper = testHelper;
  }

  @Override
  public StreamObserver<Status> createStreamObserver(String path) {
    return new StreamObserver<Status>() {
      @Override
      public void onNext(Status response) {
        testHelper.onMessage(response);
        LOG.info("Logs written to " + path);
      }

      @Override
      public void onError(Throwable t) {
        testHelper.onRpcError(t);
        LOG.error(t, "LogD failed to send a response: " + io.grpc.Status.fromThrowable(t));
      }

      @Override
      public void onCompleted() {
        LOG.info("LogD closing stream to " + path);
      }
    };
  }
}

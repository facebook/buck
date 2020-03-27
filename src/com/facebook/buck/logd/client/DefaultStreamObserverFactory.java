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

import com.google.rpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** A production implementation of StreamObserverFactory */
public class DefaultStreamObserverFactory implements StreamObserverFactory {
  private static final Logger LOG = LogManager.getLogger(DefaultStreamObserverFactory.class);

  @Override
  public StreamObserver<Status> createStreamObserver(String path) {
    return new StreamObserver<Status>() {
      @Override
      public void onNext(Status response) {
        LOG.info("Logs written to " + path);
      }

      @Override
      public void onError(Throwable t) {
        LOG.error("LogD failed to send a response: " + io.grpc.Status.fromThrowable(t), t);
      }

      @Override
      public void onCompleted() {
        LOG.info("LogD closing stream to {}", path);
      }
    };
  }
}

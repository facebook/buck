/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.remoteexecution.grpc.retry;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Interceptor for retrying client calls. Only retries in the following circumstances:
 *
 * <ul>
 *   <li>An UNAVAILABLE error is returned
 *   <li>The client only has a single request (there can be multiple responses from the server)
 *   <li>No responses in a stream have been received from the server.
 * </ul>
 */
public class RetryClientInterceptor implements ClientInterceptor {
  private final RetryPolicy retryPolicy;

  public RetryClientInterceptor(RetryPolicy retryPolicy) {
    this.retryPolicy = retryPolicy;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      final MethodDescriptor<ReqT, RespT> method,
      final CallOptions callOptions,
      final Channel next) {
    if (!method.getType().clientSendsOneMessage()) {
      return next.newCall(method, callOptions);
    }

    // TODO: Check if the method is immutable and retryable
    return new ReplayingSingleSendClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
      private int attemptNumber = 0;
      @Nullable private Future<?> future = null;

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        super.start(
            new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                responseListener) {
              private boolean receivedAResponse = false;

              @Override
              public void onClose(Status status, Metadata trailers) {
                cancelAttempt();

                if (status.getCode() != Code.UNAVAILABLE
                    || (receivedAResponse && !retryPolicy.getRestartAllStreamingCalls())
                    || attemptNumber >= retryPolicy.getMaxRetries()) {
                  super.onClose(status, trailers);
                  return;
                }

                final long delay =
                    retryPolicy.getBackoffStrategy().getDelayMilliseconds(attemptNumber);
                retryPolicy.getBeforeRetry().run();

                attemptNumber++;
                final Runnable runnable =
                    Context.current().wrap(() -> replay(next.newCall(method, callOptions)));
                future =
                    delay == 0
                        ? retryPolicy.getExecutor().submit(runnable)
                        : retryPolicy
                            .getExecutor()
                            .schedule(runnable, delay, TimeUnit.MILLISECONDS);
              }

              @Override
              public void onMessage(RespT message) {
                receivedAResponse = true;
                super.onMessage(message);
              }
            },
            headers);
      }

      @Override
      public void cancel(@Nullable String message, @Nullable Throwable cause) {
        cancelAttempt();
        super.cancel(message, cause);
      }

      private void cancelAttempt() {
        if (future != null) {
          future.cancel(true);
        }
      }
    };
  }
}

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

import com.google.common.base.Preconditions;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import javax.annotation.Nullable;

/**
 * {@link ClientCall} for single send operations that captures the outgoing calls so they can be
 * replayed on a different delegate. {@link ReplayingSingleSendClientCall} is created with an
 * initial delegate and that delegate is replaced whenever the ClientCall is replayed.
 *
 * @param <ReqT> The request type
 * @param <RespT> The response type
 */
class ReplayingSingleSendClientCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {

  private ClientCall<ReqT, RespT> delegate;
  private Listener<RespT> responseListener;
  private Metadata headers;
  private ReqT message;
  private int numMessages;
  private boolean messageCompressionEnabled = false;

  public ReplayingSingleSendClientCall(ClientCall<ReqT, RespT> delegate) {
    this.delegate = delegate;
  }

  @Override
  public void start(Listener<RespT> responseListener, Metadata headers) {
    Preconditions.checkNotNull(responseListener, "responseListener cannot be null");
    Preconditions.checkNotNull(headers, "Headers cannot be null");
    this.responseListener = responseListener;
    this.headers = headers;
    this.delegate.start(responseListener, headers);
  }

  @Override
  public void request(int numMessages) {
    this.numMessages = numMessages;
    this.delegate.request(numMessages);
  }

  @Override
  public void cancel(@Nullable String message, @Nullable Throwable cause) {
    this.delegate.cancel(message, cause);
  }

  @Override
  public void halfClose() {
    this.delegate.halfClose();
  }

  @Override
  public void sendMessage(ReqT message) {
    Preconditions.checkState(this.message == null, "Expecting only one message to be sent");
    this.message = message;
    this.delegate.sendMessage(message);
  }

  @Override
  public void setMessageCompression(boolean enabled) {
    this.messageCompressionEnabled = enabled;
  }

  @Override
  public boolean isReady() {
    return delegate.isReady();
  }

  public void replay(ClientCall<ReqT, RespT> delegate) {
    this.delegate = delegate;
    try {
      this.delegate.start(responseListener, headers);
      this.delegate.setMessageCompression(messageCompressionEnabled);
      this.delegate.request(numMessages);
      this.delegate.sendMessage(message);
      this.delegate.halfClose();
    } catch (Throwable t) {
      this.delegate.cancel(t.getMessage(), t);
    }
  }
}

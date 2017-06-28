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
package com.facebook.buck.message_ipc;

import com.google.common.base.Preconditions;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * Connection is a wrapper around MessageTransport class that transforms function calls into
 * messages.
 *
 * <p>You may specify your own interface, and call methods of that interface on an object that
 * Connection exposes to you via getRemoteObjectProxy(). Call method calls on proxy object will be
 * translated into InvocationMessage objects and sent via assigned MessageTransport object.
 *
 * <p>Example:
 *
 * <p>private interface RemoteInterface { String doString(int arg1, boolean arg2); }
 *
 * <p>MessageSerializer messageSerializer = new MessageSerializer(new ObjectMapper()); // imagine
 * you have workerProcess ready to use MessageTransport messageTransport = new
 * MessageTransport(workerProcess, messageSerializer);
 *
 * <p>Connection<RemoteInterface> connection = new Connection<>(messageTransport);
 * connection.setRemoteInterface(RemoteInterface.class, RemoteInterface.class.getClassLoader());
 * String result = connection.getRemoteObjectProxy().doString(42, true); // process result
 */
public class Connection<REMOTE> implements AutoCloseable {
  private final MessageTransport messageTransport;

  @Nullable private REMOTE remoteObjectProxy;

  private boolean isClosed = false;

  public Connection(MessageTransport messageTransport) {
    this.messageTransport = messageTransport;
  }

  @SuppressWarnings("unchecked")
  public void setRemoteInterface(Class<REMOTE> remoteInterface, ClassLoader classLoader) {
    checkNotClose();
    InvocationHandler invocationHandler =
        (proxy, method, args) -> {
          InvocationMessage invocation =
              new InvocationMessage(method.getName(), Arrays.asList(args));
          ReturnResultMessage response = messageTransport.sendMessageAndWaitForResponse(invocation);
          return response.getValue();
        };
    this.remoteObjectProxy =
        (REMOTE)
            Proxy.newProxyInstance(classLoader, new Class[] {remoteInterface}, invocationHandler);
  }

  public REMOTE getRemoteObjectProxy() {
    checkNotClose();
    Preconditions.checkNotNull(
        remoteObjectProxy, "You must set remote interface before obtaining remote object proxy.");
    return remoteObjectProxy;
  }

  @Override
  public void close() {
    checkNotClose();
    isClosed = true;
    messageTransport.close();
    remoteObjectProxy = null;
  }

  private void checkNotClose() {
    Preconditions.checkState(
        !isClosed,
        "%s <%d> is already closed",
        this.getClass().getSimpleName(),
        System.identityHashCode(this));
  }
}

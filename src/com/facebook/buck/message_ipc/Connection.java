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

import com.google.common.collect.ImmutableList;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Connection is a wrapper around MessageTransport class that transforms function calls into
 * messages.
 *
 * You may specify your own interface, and call methods of that interface on an object that
 * Connection exposes to you via getRemoteObjectProxy(). Call method calls on proxy object will be
 * translated into InvocationMessage objects and sent via assigned MessageTransport object.
 *
 * Example:
 *
 * private interface RemoteInterface {
 *   String doString(int arg1, boolean arg2);
 * }
 *
 * MessageSerializer messageSerializer = new MessageSerializer(new ObjectMapper());
 * // imagine you have workerProcess ready to use
 * MessageTransport messageTransport = new MessageTransport(workerProcess, messageSerializer);
 *
 * Connection<RemoteInterface> connection = new Connection<>(messageTransport);
 * connection.setRemoteInterface(RemoteInterface.class, RemoteInterface.class.getClassLoader());
 * String result = connection.getRemoteObjectProxy().doString(42, true);
 * // process result
 */
public class Connection<REMOTE> {
  private final MessageTransport messageTransport;

  private REMOTE remoteObjectProxy;

  public Connection(MessageTransport messageTransport) {
    this.messageTransport = messageTransport;
  }

  @SuppressWarnings("unchecked")
  public void setRemoteInterface(Class<REMOTE> remoteInterface, ClassLoader classLoader) {
    InvocationHandler invocationHandler = new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        InvocationMessage invocation = new InvocationMessage(
            method.getName(),
            ImmutableList.copyOf(args));
        ReturnResultMessage response = messageTransport.sendMessageAndWaitForResponse(invocation);
        return response.getValue();
      }
    };
    this.remoteObjectProxy = (REMOTE) Proxy.newProxyInstance(
        classLoader,
        new Class[] {remoteInterface},
        invocationHandler);
  }

  public REMOTE getRemoteObjectProxy() {
    return remoteObjectProxy;
  }
}

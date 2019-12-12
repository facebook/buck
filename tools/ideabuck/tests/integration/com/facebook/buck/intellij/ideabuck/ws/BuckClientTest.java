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

package com.facebook.buck.intellij.ideabuck.ws;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.intellij.ideabuck.test.util.MockDisposable;
import com.facebook.buck.intellij.ideabuck.test.util.MockSession;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.BuckEventsHandlerInterface;
import com.intellij.mock.MockApplication;
import com.intellij.mock.MockApplicationEx;
import com.intellij.mock.MockProjectEx;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;
import org.junit.Test;

public class BuckClientTest {

  public class TestBuckEventHandler implements BuckEventsHandlerInterface {

    private String lastMessage = "";

    public String getLastMessage() {
      return this.lastMessage;
    }

    @Override
    public void onConnect() {}

    @Override
    public void onDisconnect() {}

    @Override
    public void onMessage(String message) {
      this.lastMessage = message;
    }
  }

  @Test
  public void testConnectDisconnect() {
    Extensions.registerAreaClass("IDEA_PROJECT", null);
    MockDisposable mockDisposable = new MockDisposable();

    MockApplication application = new MockApplicationEx(mockDisposable);
    ApplicationManager.setApplication(application, mockDisposable);

    Project project = new MockProjectEx(new MockDisposable());

    TestBuckEventHandler handler = new TestBuckEventHandler();
    BuckSocket buckSocket = new BuckSocket(handler);
    BuckClientManager.getOrCreateClient(project, handler).setBuckSocket(buckSocket);

    BuckClientManager.getOrCreateClient(project, handler).connect();
    buckSocket.onConnect(new MockSession());

    BuckClientManager.getOrCreateClient(project, handler).disconnectWithoutRetry();
    buckSocket.onClose(0, "FOO");

    assertFalse(BuckClientManager.getOrCreateClient(project, handler).isConnected());
  }

  @Test
  public void hasBuckDisconnectedThenWeReconnectIfSoSpecified() {
    Extensions.registerAreaClass("IDEA_PROJECT", null);
    MockDisposable mockDisposable = new MockDisposable();

    MockApplication application = new MockApplicationEx(mockDisposable);
    ApplicationManager.setApplication(application, mockDisposable);

    Project project = new MockProjectEx(new MockDisposable());

    TestBuckEventHandler handler = new TestBuckEventHandler();
    BuckSocket buckSocket = new BuckSocket(handler);
    BuckClientManager.getOrCreateClient(project, handler).setBuckSocket(buckSocket);

    BuckClientManager.getOrCreateClient(project, handler).connect();
    buckSocket.onConnect(new MockSession());

    BuckClientManager.getOrCreateClient(project, handler).disconnectWithRetry();
    buckSocket.onClose(0, "FOO");
    buckSocket.onConnect(new MockSession());

    assertTrue(BuckClientManager.getOrCreateClient(project, handler).isConnected());
  }

  @Test
  public void testMessages() {
    Extensions.registerAreaClass("IDEA_PROJECT", null);
    MockDisposable mockDisposable = new MockDisposable();

    MockApplication application = new MockApplicationEx(mockDisposable);
    ApplicationManager.setApplication(application, mockDisposable);
    Project project = new MockProjectEx(new MockDisposable());

    TestBuckEventHandler handler = new TestBuckEventHandler();
    BuckClient client = BuckClientManager.getOrCreateClient(project, handler);

    // Set the socket we control
    BuckSocket socket = new BuckSocket(handler);

    client.setBuckSocket(socket);

    client.connect();

    assertEquals("", handler.getLastMessage());

    socket.onMessage("some text");
    assertEquals("some text", handler.getLastMessage());

    socket.onMessage("some text 1");
    socket.onMessage("some text 2");
    socket.onMessage("some text 3");
    socket.onMessage("some text 4");
    assertEquals("some text 4", handler.getLastMessage());
  }
}

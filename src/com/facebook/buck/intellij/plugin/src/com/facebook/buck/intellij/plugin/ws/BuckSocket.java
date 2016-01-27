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

package com.facebook.buck.intellij.plugin.ws;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.facebook.buck.intellij.plugin.ws.buckevents.BuckEventHandlerInterface;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

// Set the maximum message text size to 24 MB
@WebSocket(maxTextMessageSize = 24 * 1024 * 1024)
public class BuckSocket {
    private final CountDownLatch closeLatch;

    private BuckEventHandlerInterface mEventsHandler;

    @SuppressWarnings("unused")
    private Session session;

    public BuckSocket(
        BuckEventHandlerInterface eventsHandler
    ) {
        mEventsHandler = eventsHandler;
        this.closeLatch = new CountDownLatch(1);
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        this.session = null;
        this.closeLatch.countDown();
        mEventsHandler.onDisconnect();
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        this.session = session;
        mEventsHandler.onConnect();
    }

    @OnWebSocketMessage
    public void onMessage(String msg) {
        mEventsHandler.onMessage(msg);
    }

    public void sendMessage(String msg)
            throws InterruptedException, ExecutionException, TimeoutException {
        Future<Void> fut;
        fut = session.getRemote().sendStringByFuture(msg);
        fut.get(1, TimeUnit.SECONDS);
    }
}

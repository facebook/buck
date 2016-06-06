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

import com.facebook.buck.intellij.plugin.ws.buckevents.BuckEventsHandlerInterface;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// Set the maximum message text size to 24 MB
@WebSocket(maxTextMessageSize = 24 * 1024 * 1024)
public class BuckSocket {
    private AtomicBoolean connected;
    private AtomicLong lastActionTime;
    private BuckEventsHandlerInterface eventsHandler;

    @SuppressWarnings("unused")
    private Session session;

    public BuckSocket(BuckEventsHandlerInterface eventsHandler) {
        this.connected = new AtomicBoolean(false);
        this.lastActionTime = new AtomicLong(0);
        this.eventsHandler = eventsHandler;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public long getTimeSinceLastAction() {
        return new Date().getTime() - lastActionTime.get();
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        connected.set(false);
        this.session = null;
        eventsHandler.onDisconnect();
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        connected.set(true);
        this.session = session;
        eventsHandler.onConnect();
    }

    @OnWebSocketMessage
    public void onMessage(String msg) {
        lastActionTime.set(new Date().getTime());
        eventsHandler.onMessage(msg);
    }

    public void sendMessage(String msg)
            throws InterruptedException, ExecutionException, TimeoutException {
        lastActionTime.set(new Date().getTime());
        Future<Void> fut;
        fut = session.getRemote().sendStringByFuture(msg);
        fut.get(1, TimeUnit.SECONDS);
    }
}

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

import java.net.URI;
import java.util.Date;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.facebook.buck.intellij.plugin.ws.buckevents.BuckEventHandlerInterface;
import com.intellij.openapi.diagnostic.Logger;
import org.eclipse.jetty.websocket.client.WebSocketClient;

public class BuckClient {

    private int mPort = -1;
    private String mHost = "localhost";;

    private WebSocketClient mWSClient = new WebSocketClient();
    private BuckSocket mWSSocket;
    private boolean mConnected = false;
    private long mLastActionTime = 0;
    private static final long PING_PERIOD = 1000 * 60;
    private Object syncObject = new Object();

    private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;
    private ScheduledFuture<?> scheduledFuture;

    public boolean isConnected() {
        return mConnected;
    }

    public BuckClient(String host, int port, final BuckEventHandlerInterface handler) {

        scheduledThreadPoolExecutor =
            new ScheduledThreadPoolExecutor(
                    1,
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            return new Thread(r, "ideabuck keep alive");
                        }
                    }
            );
        mWSSocket = new BuckSocket(
            new BuckEventHandlerInterface() {
                @Override
                public void onConnect() {
                    handler.onConnect();
                    BuckClient.this.mConnected = true;
                }

                @Override
                public void onDisconnect() {
                    handler.onDisconnect();
                    BuckClient.this.mConnected = false;
                }

                @Override
                public void onMessage(String message) {
                    synchronized (syncObject) {
                        BuckClient.this.mLastActionTime = (new Date()).getTime();
                    }
                    handler.onMessage(message);
                }
            }
        );

        mHost = host;
        mPort = port;
    }

    public BuckClient(int port, BuckEventHandlerInterface handler) {
        this("localhost", port, handler);
    }

    public BuckClient() {
        this(-1, null);
    }

    public void setSocket(BuckSocket socket) {
        mWSSocket = socket;
    }

    public void connect() {
        if (mPort != -1) {
            try {
                mWSClient.start();
                URI uri = new URI("ws://" + mHost + ":" + mPort + "/ws/build");
                mWSClient.connect(mWSSocket, uri);
                synchronized (syncObject) {
                    mLastActionTime = (new Date()).getTime();
                }
                mConnected = true;
                scheduledFuture = scheduledThreadPoolExecutor.scheduleAtFixedRate(
                        new Runnable() {
                            @Override
                            public void run() {
                                BuckClient.this.ping();
                            }
                        },
                        10,
                        10,
                        TimeUnit.SECONDS);
            } catch (Throwable t) {
                mConnected = false;
            }
        }
    }
    public void disconnect() {
        if (mConnected) {
            scheduledFuture.cancel(true);
            try {
                mWSClient.stop();
                mConnected = false;
            } catch (Throwable t) {
                Logger.getInstance(this.getClass()).error(
                        "Could not disconnect from buck. " + t.getMessage());
            }
        }
    }

    private void ping() {
        if ((new Date()).getTime() - mLastActionTime < PING_PERIOD) {
            return;
        }
        if (mConnected) {
            try {
                mWSSocket.sendMessage("ping");
                synchronized (syncObject) {
                    mLastActionTime = (new Date()).getTime();
                }
            } catch (Throwable t) {
                Logger.getInstance(this.getClass()).error("Could not send ping");
            }
        }
    }


}

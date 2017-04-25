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

package com.facebook.buck.intellij.ideabuck.ws;

import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.facebook.buck.intellij.ideabuck.config.BuckWSServerPortUtils;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.BuckEventsHandlerInterface;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.execution.ExecutionException;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.websocket.client.WebSocketClient;

public class BuckClient {
  private static final Logger LOG = Logger.getInstance(BuckClient.class);
  private BuckSocket mBuckSocket;
  private WebSocketClient mWSClient;
  private AtomicBoolean mConnecting;
  private Project mProject;

  BuckClient(final BuckEventsHandlerInterface buckEventHandler, Project project) {
    mWSClient = new WebSocketClient();
    mProject = project;
    mConnecting = new AtomicBoolean(false);

    mBuckSocket =
        new BuckSocket(
            new BuckEventsHandlerInterface() {
              @Override
              public void onConnect() {
                buckEventHandler.onConnect();
                mConnecting.set(false);
              }

              @Override
              public void onDisconnect() {
                buckEventHandler.onDisconnect();
              }

              @Override
              public void onMessage(String message) {
                buckEventHandler.onMessage(message);
              }
            });
  }

  public void connect() {
    if (isConnected() || mConnecting.get()) {
      return;
    }
    mConnecting.set(true);
    ApplicationManager.getApplication()
        .executeOnPooledThread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  int port = BuckWSServerPortUtils.getPort(mProject.getBasePath());
                  // Connect to WebServer
                  connectToWebServer("localhost", port);
                } catch (NumberFormatException e) {
                  LOG.error(e);
                } catch (ExecutionException e) {
                  LOG.error(e);
                } catch (IOException e) {
                  LOG.error(e);
                } catch (RuntimeException e) {
                  if (!mProject.isDisposed()) {
                    BuckModule buckModule = mProject.getComponent(BuckModule.class);
                    buckModule.attachIfDetached();
                    buckModule.getBuckEventsConsumer().consumeConsoleEvent(e.toString());
                  }
                }
              }
            });
  }

  @VisibleForTesting
  protected void setBuckSocket(BuckSocket buckSocket) {
    mBuckSocket = buckSocket;
  }

  private void connectToWebServer(String host, int port) {
    try {
      mWSClient.start();
      URI uri = new URI("ws://" + host + ":" + port + "/ws/build");
      mWSClient.connect(mBuckSocket, uri);
    } catch (Throwable t) {
      LOG.error(t);
      mConnecting.set(false);
    }
  }

  public boolean isConnected() {
    return mBuckSocket.isConnected();
  }

  public void disconnectWithRetry() {
    disconnect(true);
  }

  public void disconnectWithoutRetry() {
    disconnect(false);
  }

  private void disconnect(final boolean retry) {
    ApplicationManager.getApplication()
        .executeOnPooledThread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  mWSClient.stop();
                  if (retry) {
                    connect();
                  } else {
                    mWSClient.destroy();
                    BuckClientManager.removeClient(mProject);
                  }
                } catch (InterruptedException e) {
                  LOG.error("Could not disconnect from buck. " + e);
                } catch (Throwable t) {
                  LOG.error("Could not disconnect from buck. " + t.getMessage());
                }
              }
            });
  }
}

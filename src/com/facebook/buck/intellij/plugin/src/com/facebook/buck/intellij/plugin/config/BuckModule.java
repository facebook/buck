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

package com.facebook.buck.intellij.plugin.config;

import com.facebook.buck.intellij.plugin.autodeps.BuckAutoDepsContributor;
import com.facebook.buck.intellij.plugin.debugger.AndroidDebugger;
import com.facebook.buck.intellij.plugin.file.BuckFileUtil;
import com.facebook.buck.intellij.plugin.ui.BuckEventsConsumer;
import com.facebook.buck.intellij.plugin.ui.BuckToolWindowFactory;
import com.facebook.buck.intellij.plugin.ui.BuckUIManager;
import com.facebook.buck.intellij.plugin.ui.utils.BuckPluginNotifications;
import com.facebook.buck.intellij.plugin.ws.BuckClient;
import com.facebook.buck.intellij.plugin.ws.buckevents.BuckEventsHandler;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckEventsConsumerFactory;
import com.facebook.buck.util.HumanReadableException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;

import java.io.IOException;

public final class BuckModule implements ProjectComponent {

    private Project mProject;
    private BuckClient mClient = new BuckClient();
    private BuckEventsHandler mEventHandler;
    private BuckEventsConsumer mBuckEventsConsumer;
    private static final Logger LOG = Logger.getInstance(BuckModule.class);

    public BuckModule(final Project project) {
        mProject = project;
        mEventHandler = new BuckEventsHandler(
            new BuckEventsConsumerFactory(mProject),
            new Runnable() {
                @Override
                public void run() {
                    ApplicationManager.getApplication().invokeLater(new Runnable() {
                        @Override
                        public void run() {
                        BuckToolWindowFactory.outputConsoleMessage(
                            project,
                            "Connected to buck!\n",
                            ConsoleViewContentType.SYSTEM_OUTPUT
                        );
                        }
                    });
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    ApplicationManager.getApplication().invokeLater(new Runnable() {
                        @Override
                        public void run() {
                        BuckToolWindowFactory.outputConsoleMessage(
                            project,
                            "Disconnected from buck!\n",
                            ConsoleViewContentType.SYSTEM_OUTPUT
                        );
                        }
                    });
                    BuckModule mod = project.getComponent(BuckModule.class);
                    mod.disconnect();
                }
            }
        );

        if (!UISettings.getInstance().SHOW_MAIN_TOOLBAR) {
            BuckPluginNotifications.notifyActionToolbar(mProject);
        }

        mBuckEventsConsumer = new BuckEventsConsumer(project);
    }

    @Override
    public String getComponentName() {
        return "buck.connector";
    }

    @Override
    public void initComponent() {}

    @Override
    public void disposeComponent() {}

    @Override
    public void projectOpened() {
        PsiDocumentManager manager = PsiDocumentManager.getInstance(mProject);
        manager.addListener(new BuckAutoDepsContributor(mProject));
        connect();
    }

    @Override
    public void projectClosed() {
        disconnect();
        AndroidDebugger.disconnect();
        if (mBuckEventsConsumer != null) {
            mBuckEventsConsumer.detach();
        }
    }

    public boolean isConnected() {
        return mClient.isConnected();
    }

    public void disconnect() {
        if (mClient.isConnected()) {
            mClient.disconnect();
        }
    }

    public void connect() {
        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
                if (!mClient.isConnected()) {
                    BuckWSServerPortUtils wsPortUtils = new BuckWSServerPortUtils();
                    try {
                        int port = wsPortUtils.getPort(BuckModule.this.mProject.getBasePath());
                        mClient = new BuckClient(port, mEventHandler);
                        // Initiate connecting
                        BuckModule.this.mClient.connect();
                    } catch (NumberFormatException e) {
                        LOG.error(e);
                    } catch (ExecutionException e) {
                        LOG.error(e);
                    } catch (IOException e) {
                        LOG.error(e);
                    } catch (HumanReadableException e) {
                        mBuckEventsConsumer.consumeConsoleEvent(e.toString());
                    }
                }
            }
        });
        BuckFileUtil.setBuckFileType();
    }

    public void attach(String target) {
        mBuckEventsConsumer.detach();
        mBuckEventsConsumer.attach(target, BuckUIManager.getInstance(mProject).getTreeModel());
    }

    public BuckEventsConsumer getBuckEventsConsumer() {
        return mBuckEventsConsumer;
    }
}

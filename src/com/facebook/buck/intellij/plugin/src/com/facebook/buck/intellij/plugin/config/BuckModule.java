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

import com.facebook.buck.intellij.plugin.debugger.AndroidDebugger;
import com.facebook.buck.intellij.plugin.file.BuckFileType;
import com.facebook.buck.intellij.plugin.ui.BuckEventsConsumer;
import com.facebook.buck.intellij.plugin.ui.BuckToolWindowFactory;
import com.facebook.buck.intellij.plugin.ui.BuckUIManager;
import com.facebook.buck.intellij.plugin.ws.BuckClient;
import com.facebook.buck.intellij.plugin.ws.buckevents.BuckEventsHandler;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckEventsConsumerFactory;
import com.facebook.buck.util.HumanReadableException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileNameMatcher;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.fileTypes.impl.FileTypeManagerImpl;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.util.List;

public final class BuckModule implements ProjectComponent {

    private Project mProject;
    private BuckClient mClient = new BuckClient();
    private BuckEventsHandler mEventHandler;
    private BuckEventsConsumer mBu;
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
        connect();
    }

    @Override
    public void projectClosed() {
        disconnect();
        AndroidDebugger.disconnect();
    }

    public boolean isConnected() {
        return mClient.isConnected();
    }

    public void disconnect() {
        if (mClient.isConnected()) {
            if (mBu != null) {
                mBu.detach();
            }
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
                        if (mBu == null) {
                          attach(new BuckEventsConsumer(mProject), "");
                        }
                        mBu.consumeConsoleEvent(e.toString());
                    }
                }
            }
        });

        ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
                FileTypeManager fileTypeManager = FileTypeManagerImpl.getInstance();

              FileType fileType = fileTypeManager
                  .getFileTypeByFileName(BuckFileType.INSTANCE.getDefaultExtension());

              // Remove all FileType associations for BUCK files that are not BuckFileType
              while (!(fileType instanceof  BuckFileType || fileType instanceof UnknownFileType)) {
                List<FileNameMatcher> fileNameMatchers = fileTypeManager.getAssociations(fileType);

                for (FileNameMatcher fileNameMatcher : fileNameMatchers) {
                  if (fileNameMatcher.accept(BuckFileType.INSTANCE.getDefaultExtension())) {
                    fileTypeManager.removeAssociation(fileType, fileNameMatcher);
                  }
                }

                fileType = fileTypeManager
                    .getFileTypeByFileName(BuckFileType.INSTANCE.getDefaultExtension());
              }
            }

        });
    }

    public void attach(BuckEventsConsumer bu, String target) {
        if (mBu != null) {
            mBu.detach();
        }
        mBu = bu;
        mBu.attach(target, BuckUIManager.getInstance(mProject).getTreeModel());
    }
}

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
package com.facebook.buck.intellij.ideabuck.format;

import com.facebook.buck.intellij.ideabuck.config.BuckExecutableSettingsProvider;
import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.intellij.AppTopics;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

/** Reformats build files using {@code buildifier} before saving. */
public class BuildifierAutoFormatter implements ProjectComponent, Disposable {

  private static final Logger LOGGER = Logger.getInstance(BuildifierAutoFormatter.class);

  private final Project mProject;
  private final BuckProjectSettingsProvider mProjectSettingsProvider;
  private final BuckExecutableSettingsProvider mExecutableSettingsProvider;
  private MessageBusConnection mMessageBusConnection;

  public BuildifierAutoFormatter(
      Project project,
      BuckProjectSettingsProvider projectSettingsProvider,
      BuckExecutableSettingsProvider executableSettingsProvider) {
    mProject = project;
    mProjectSettingsProvider = projectSettingsProvider;
    mExecutableSettingsProvider = executableSettingsProvider;
  }

  @Override
  public void initComponent() {
    mMessageBusConnection = mProject.getMessageBus().connect(this);
    mMessageBusConnection.subscribe(
        AppTopics.FILE_DOCUMENT_SYNC,
        new FileDocumentManagerListener() {
          @Override
          public void beforeDocumentSaving(@NotNull Document document) {
            if (mProjectSettingsProvider.isAutoFormatOnSave()) {
              BuildifierUtil.reformatText(mProject, document.getText())
                  .ifPresent(document::setText);
            }
          }
        });
  }

  @Override
  public void disposeComponent() {
    if (mMessageBusConnection != null) {
      mMessageBusConnection.disconnect();
      mMessageBusConnection = null;
    }
  }

  @Override
  public void dispose() {
    disposeComponent();
  }
}

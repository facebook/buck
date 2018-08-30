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

package com.facebook.buck.intellij.ideabuck.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.intellij.ideabuck.test.util.MockBuckUIManager;
import com.facebook.buck.intellij.ideabuck.test.util.MockDisposable;
import com.facebook.buck.intellij.ideabuck.test.util.MockTestResults;
import com.facebook.buck.intellij.ideabuck.test.util.MockTreeModelListener;
import com.facebook.buck.intellij.ideabuck.test.util.MyMockApplication;
import com.google.common.collect.ImmutableSet;
import com.intellij.mock.Mock;
import com.intellij.mock.MockApplication;
import com.intellij.mock.MockFileDocumentManagerImpl;
import com.intellij.mock.MockProject;
import com.intellij.mock.MockProjectEx;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindowManager;
import java.lang.reflect.Field;
import org.easymock.EasyMock;
import org.junit.Test;

public class BuckEventsConsumerTest {
  @Test
  public void hasBuckModuleAttachReceivedNullTargetThenWeShowNone()
      throws NoSuchFieldException, IllegalAccessException {
    MockProject project = new MockProjectEx(new MockDisposable());
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);

    buckEventsConsumer.attach(null);

    Field privateStringField = BuckEventsConsumer.class.getDeclaredField("mTarget");

    privateStringField.setAccessible(true);

    String fieldValue = (String) privateStringField.get(buckEventsConsumer);
    assertEquals(fieldValue, "NONE");
  }

  public MockProject initProject() {
    Extensions.registerAreaClass("IDEA_PROJECT", null);
    MockProject mockProject = new MockProjectEx(new MockDisposable());
    return mockProject;
  }

  public BuckEventsConsumer initialiseEventsConsumer(MockProject project) {
    MockDisposable mockDisposable = new MockDisposable();

    MockApplication application = new MyMockApplication(mockDisposable);
    ApplicationManager.setApplication(application, mockDisposable);
    application.registerService(
        FileDocumentManager.class, new MockFileDocumentManagerImpl(null, null));
    application.registerService(
        VirtualFileManager.class, EasyMock.createMock(VirtualFileManager.class));
    project.registerService(BuckUIManager.class, new MockBuckUIManager(project));
    project.registerService(ToolWindowManager.class, new Mock.MyToolWindowManager());
    return new BuckEventsConsumer(project);
  }

  public MockTreeModelListener addListeners(MockProject project) {
    BuckUIManager buckUIManager = BuckUIManager.getInstance(project);
    final MockTreeModelListener listener = new MockTreeModelListener();
    buckUIManager
        .getBuckTreeViewPanel()
        .getModifiableModel()
        .addTreeModelListener(_tree -> listener);
    buckUIManager
        .getBuckTreeViewPanel()
        .getModifiableModel()
        .addTreeModelListener(_tree -> new MockTreeModelListener());
    buckUIManager
        .getBuckTreeViewPanel()
        .getModifiableModel()
        .addTreeModelListener(_tree -> new MockTreeModelListener());
    return listener;
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInAttachThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeBuckBuildProgressUpdateThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeBuckBuildProgressUpdate(0, 0);
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeBuildEndThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeBuildEnd(0);
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeBuildStartThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeBuildStart(0);
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeBuckProjectGenerationFinishedThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeBuckProjectGenerationFinished(0);
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeBuckProjectGenerationProgressThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeBuckProjectGenerationProgress(0, 0);
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeBuckProjectGenerationStartedThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeBuckProjectGenerationStarted(0);
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeCompilerErrorThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeCompilerError("", 0, "", ImmutableSet.<String>of());
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeConsoleEventThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeConsoleEvent("");
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeInstallFinishedThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeInstallFinished(0, "");
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeParseRuleEndThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeParseRuleEnd(0);
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeParseRuleStartThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeParseRuleStart(0);
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeParseRuleProgressUpdateThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeParseRuleProgressUpdate(0, 0);
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeTestResultsAvailableThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeTestResultsAvailable(0, new MockTestResults());
    assertFalse(listener.calledOnWrongThread);
    buckEventsConsumer.consumeTestResultsAvailable(1, new MockTestResults());
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeTestRunCompleteThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeTestRunComplete(0);
    assertFalse(listener.calledOnWrongThread);
  }

  @Test
  public void hasUIWorkBeenDoneOnNonAwtThreadInConsumeTestRunStartedThenFail() {
    MockProject project = initProject();
    BuckEventsConsumer buckEventsConsumer = initialiseEventsConsumer(project);
    MockTreeModelListener listener = addListeners(project);

    buckEventsConsumer.attach(null);
    assertFalse(listener.calledOnWrongThread);
    assertTrue(buckEventsConsumer.isAttached());

    buckEventsConsumer.consumeTestRunStarted(0);
    assertFalse(listener.calledOnWrongThread);
  }
}

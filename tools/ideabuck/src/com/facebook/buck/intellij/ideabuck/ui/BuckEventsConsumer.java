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

package com.facebook.buck.intellij.ideabuck.ui;

import com.facebook.buck.intellij.ideabuck.actions.BuckInstallDebugAction;
import com.facebook.buck.intellij.ideabuck.config.BuckExecutableSettingsProvider;
import com.facebook.buck.intellij.ideabuck.debugger.AndroidDebugger;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckFileErrorNode;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTextNode;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTextNode.TextType;
import com.facebook.buck.intellij.ideabuck.ui.utils.CompilerErrorItem;
import com.facebook.buck.intellij.ideabuck.ui.utils.ErrorExtractor;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckBuildEndConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckBuildProgressUpdateConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckBuildStartConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckConsoleEventConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckInstallFinishedConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckProjectGenerationFinishedConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckProjectGenerationProgressConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckProjectGenerationStartedConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.CompilerErrorConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.RulesParsingEndConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.RulesParsingProgressUpdateConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.RulesParsingStartConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.TestResultsAvailableConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.TestRunCompleteConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.TestRunStartedConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.parts.TestCaseSummary;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.parts.TestResults;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.parts.TestResultsSummary;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BuckEventsConsumer
    implements BuckBuildStartConsumer,
        BuckBuildEndConsumer,
        BuckBuildProgressUpdateConsumer,
        BuckProjectGenerationFinishedConsumer,
        BuckProjectGenerationProgressConsumer,
        BuckProjectGenerationStartedConsumer,
        RulesParsingStartConsumer,
        RulesParsingEndConsumer,
        RulesParsingProgressUpdateConsumer,
        TestRunStartedConsumer,
        TestRunCompleteConsumer,
        TestResultsAvailableConsumer,
        BuckConsoleEventConsumer,
        CompilerErrorConsumer,
        BuckInstallFinishedConsumer {

  private Project mProject;
  private final BuckUIManager mBuckUIManager;

  public BuckEventsConsumer(Project project) {
    mProject = project;
    mBuckUIManager = BuckUIManager.getInstance(project);
  }

  private MessageBusConnection mConnection;
  private Map<String, List<String>> mErrors =
      Collections.synchronizedMap(new HashMap<String, List<String>>());
  private double mBuildProgressValue = 0;
  private double mParseProgressValue = 0;
  private double mProjectGenerationProgressValue = 0;

  private long mMainBuildStartTimestamp = 0;
  private long mMainBuildEndTimestamp = 0;

  private long mParseFilesStartTimestamp = 0;
  private long mParseFilesEndTimestamp = 0;
  private long mTestingEndTimestamp = 0;
  private long mTestingStartTimestamp = 0;
  private List<TestResults> mTestResultsList =
      Collections.synchronizedList(new LinkedList<TestResults>());
  private boolean attached = false;

  private long mProjectGenerationStartTimestamp = 0;
  private long mProjectGenerationFinishedTimestamp = 0;

  BuckTextNode mCurrentBuildRootElement;
  BuckTextNode mBuildProgress;
  BuckTextNode mParseProgress;
  BuckTextNode mTestResults;
  BuckTextNode mProjectGenerationProgress;

  public void detach() {
    if (mConnection != null) {
      mConnection.disconnect();
    }
    mBuildProgress = null;
    mParseProgress = null;
    mTestResults = null;
    mProjectGenerationProgress = null;
    attached = false;
    mBuildProgressValue = 0;
    mParseProgressValue = 0;
    mProjectGenerationProgressValue = 0;
    mTestResultsList = Collections.synchronizedList(new LinkedList<TestResults>());
    mErrors = Collections.synchronizedMap(new HashMap<String, List<String>>());
  }

  public boolean isAttached() {
    return attached;
  }

  /**
   * Add a new BuckTextNode in the BuckTreeViewPanel, which becomes the root when adding new nodes
   * in the event consumers
   *
   * @param message The text to be displayed in the BuckTextNode
   */
  public void attach(String message) {
    mCurrentBuildRootElement = new BuckTextNode(message, TextType.INFO);
    mBuckUIManager
        .getBuckTreeViewPanel()
        .getModifiableModel()
        .addChild(mBuckUIManager.getBuckTreeViewPanel().getRoot(), mCurrentBuildRootElement);

    mMainBuildStartTimestamp = 0;

    mConnection = mProject.getMessageBus().connect();

    mConnection.subscribe(
        BuckProjectGenerationFinishedConsumer.PROJECT_GENERATION_FINISHED_CONSUMER, this);
    mConnection.subscribe(
        BuckProjectGenerationStartedConsumer.PROJECT_GENERATION_STARTED_CONSUMER, this);
    mConnection.subscribe(
        BuckProjectGenerationProgressConsumer.PROJECT_GENERATION_PROGRESS_CONSUMER, this);

    mConnection.subscribe(BuckBuildStartConsumer.BUCK_BUILD_START, this);
    mConnection.subscribe(BuckBuildEndConsumer.BUCK_BUILD_END, this);
    mConnection.subscribe(BuckBuildProgressUpdateConsumer.BUCK_BUILD_PROGRESS_UPDATE, this);

    mConnection.subscribe(RulesParsingStartConsumer.BUCK_PARSE_RULE_START, this);
    mConnection.subscribe(RulesParsingEndConsumer.BUCK_PARSE_RULE_END, this);
    mConnection.subscribe(RulesParsingProgressUpdateConsumer.BUCK_PARSE_PROGRESS_UPDATE, this);

    mConnection.subscribe(CompilerErrorConsumer.COMPILER_ERROR_CONSUMER, this);
    mConnection.subscribe(BuckConsoleEventConsumer.BUCK_CONSOLE_EVENT, this);

    mConnection.subscribe(TestRunStartedConsumer.BUCK_TEST_RUN_STARTED, this);
    mConnection.subscribe(TestRunCompleteConsumer.BUCK_TEST_RUN_COMPLETE, this);
    mConnection.subscribe(TestResultsAvailableConsumer.BUCK_TEST_RESULTS_AVAILABLE, this);

    mConnection.subscribe(BuckInstallFinishedConsumer.INSTALL_FINISHED_CONSUMER, this);

    attached = true;
  }

  @Override
  public void consumeBuckBuildProgressUpdate(long timestamp, double progressValue) {
    if (mBuildProgress == null) {
      consumeBuildStart(timestamp);
    }
    mBuildProgressValue = progressValue;
    final String message = "Current build progress: " + Math.round(mBuildProgressValue * 100) + "%";

    mBuckUIManager.getBuckTreeViewPanel().getModifiableModel().setNodeText(mBuildProgress, message);
  }

  @Override
  public void consumeParseRuleStart(long timestamp) {
    mParseFilesStartTimestamp = timestamp;

    // start may be called before of after progress update
    if (BuckEventsConsumer.this.mParseProgress == null) {
      BuckEventsConsumer.this.mParseProgress =
          new BuckTextNode(
              "Current file parsing progress: " + Math.round(mParseProgressValue * 100) + "%",
              TextType.INFO);
      mBuckUIManager
          .getBuckTreeViewPanel()
          .getModifiableModel()
          .addChild(mCurrentBuildRootElement, mParseProgress);
    }
  }

  @Override
  public void consumeParseRuleEnd(long timestamp) {
    consumeParseRuleProgressUpdate(timestamp, 1.0f);
    mParseFilesEndTimestamp = timestamp;
    float duration = (mParseFilesEndTimestamp - mParseFilesStartTimestamp) / 1000;
    final String message = "File parsing ended, took " + duration + " seconds!";

    mBuckUIManager.getBuckTreeViewPanel().getModifiableModel().setNodeText(mParseProgress, message);
  }

  @Override
  public void consumeParseRuleProgressUpdate(long timestamp, double progressValue) {
    if (mParseProgress == null) {
      consumeParseRuleStart(timestamp);
    }
    if (mParseProgressValue != 1.0f) {
      mParseProgressValue = progressValue;
      final String message =
          "Current file parsing progress: " + Math.round(mParseProgressValue * 100) + "%";

      mBuckUIManager
          .getBuckTreeViewPanel()
          .getModifiableModel()
          .setNodeText(mParseProgress, message);
    }
  }

  @Override
  public void consumeCompilerError(
      String target, long timestamp, String error, ImmutableSet<String> suggestions) {
    List<String> existingErrors;
    if (mErrors.containsKey(target)) {
      existingErrors = mErrors.get(target);
    } else {
      existingErrors = new ArrayList<String>();
    }
    existingErrors.add(error);
    mErrors.put(target, existingErrors);
  }

  private BuckTextNode buildTargetErrorNode(String target, List<String> errorMessages) {
    BuckTextNode targetNode = new BuckTextNode(target, TextType.ERROR);
    for (String currentError : errorMessages) {
      ErrorExtractor errorExtractor = new ErrorExtractor(currentError);
      ImmutableList<CompilerErrorItem> errorItems = errorExtractor.getErrors();

      BuckFileErrorNode currentFileErrorNode = null;
      String currentFilePath = "";

      for (CompilerErrorItem currentErrorItem : errorItems) {
        if (!currentErrorItem.getFilePath().equals(currentFilePath)) {
          if (currentFileErrorNode != null) {
            // Add to parent
            targetNode.add(currentFileErrorNode);
          }
          // Create the new node, for the new file
          currentFileErrorNode = new BuckFileErrorNode(currentErrorItem.getFilePath());
          currentFileErrorNode.addErrorItem(currentErrorItem);
          currentFilePath = currentErrorItem.getFilePath();
        } else {
          currentFileErrorNode.addErrorItem(currentErrorItem);
        }
      }

      if (currentFileErrorNode != null) {
        // Add the leftovers
        targetNode.add(currentFileErrorNode);
      }
    }
    return targetNode;
  }

  private void displayErrors() {
    if (mErrors.size() > 0) {
      if (!mBuckUIManager.getBuckToolWindow().isMainToolWindowVisible()) {
        mBuckUIManager.getBuckToolWindow().showMainToolWindow();
      }
      Set<String> targetsWithErrors = mErrors.keySet();
      for (String target : targetsWithErrors) {
        List<String> errorMessages = mErrors.get(target);
        if (errorMessages.size() > 0) {
          BuckTextNode targetNode = buildTargetErrorNode(target, errorMessages);
          mBuckUIManager
              .getBuckTreeViewPanel()
              .getModifiableModel()
              .addChild(mCurrentBuildRootElement, targetNode);
        }
      }
    }
  }

  @Override
  public void consumeBuildEnd(final long timestamp) {
    mMainBuildEndTimestamp = timestamp;
    float duration = (mMainBuildEndTimestamp - mMainBuildStartTimestamp) / 1000;
    final String message = "Build ended, took " + duration + " seconds!";

    int errors = 0;
    int warnings = 0;

    for (String cTarget : mErrors.keySet()) {
      List<String> currentErrorMessages = mErrors.get(cTarget);
      for (String currentErrorMessage : currentErrorMessages) {
        ErrorExtractor errorExtractor = new ErrorExtractor(currentErrorMessage);
        for (CompilerErrorItem currentErrorItem : errorExtractor.getErrors()) {
          if (currentErrorItem.getType() == CompilerErrorItem.Type.ERROR) {
            errors++;
          } else {
            warnings++;
          }
        }
      }
    }

    String errorsMessage = "";

    if (errors != 0) {
      errorsMessage = "Found " + errors + " errors";
    }
    if (warnings != 0) {
      if (errors != 0) {
        errorsMessage += " and " + warnings + " warnings";
      } else {
        errorsMessage = "Found " + warnings + " warnings";
      }
    }

    if (errorsMessage.length() > 0) {
      errorsMessage += "!";
    }

    final String errorsMessageToUse = errorsMessage;

    // set progress to 100%
    consumeBuckBuildProgressUpdate(timestamp, 1f);

    if (errorsMessageToUse.length() > 0) {
      mBuckUIManager
          .getBuckTreeViewPanel()
          .getModifiableModel()
          .removeAllChildren(mCurrentBuildRootElement);
      BuckTextNode errorsMessageNode = new BuckTextNode(errorsMessageToUse, TextType.ERROR);
      mBuckUIManager
          .getBuckTreeViewPanel()
          .getModifiableModel()
          .addChild(mCurrentBuildRootElement, errorsMessageNode);

      // Display errors
      BuckEventsConsumer.this.displayErrors();
    }

    mBuckUIManager.getBuckTreeViewPanel().getModifiableModel().setNodeText(mBuildProgress, message);
  }

  @Override
  public void consumeBuildStart(long timestamp) {
    mMainBuildStartTimestamp = timestamp;
    if (BuckEventsConsumer.this.mBuildProgress == null) {
      BuckEventsConsumer.this.mBuildProgress =
          new BuckTextNode(
              "Current build progress: " + Math.round(mBuildProgressValue * 100) + "%",
              TextType.INFO);
      // start may be called before of after progress update
      mBuckUIManager
          .getBuckTreeViewPanel()
          .getModifiableModel()
          .addChild(mCurrentBuildRootElement, mBuildProgress);
    }
  }

  @Override
  public void consumeBuckProjectGenerationFinished(long timestamp) {
    consumeBuckProjectGenerationProgress(timestamp, 1.0f);
    mProjectGenerationFinishedTimestamp = timestamp;
    float duration =
        (mProjectGenerationFinishedTimestamp - mProjectGenerationStartTimestamp) / 1000;
    final String message = "Project generation ended, took " + duration + " seconds!";

    mBuckUIManager
        .getBuckTreeViewPanel()
        .getModifiableModel()
        .setNodeText(mProjectGenerationProgress, message, true);
  }

  @Override
  public void consumeBuckProjectGenerationProgress(long timestamp, double progressValue) {
    if (mProjectGenerationProgress == null) {
      consumeBuckProjectGenerationStarted(timestamp);
    }
    if (mProjectGenerationProgressValue != 1.0f) {
      mProjectGenerationProgressValue = progressValue;
      final String message =
          "Current file parsing progress: "
              + Math.round(mProjectGenerationProgressValue * 100)
              + "%";

      mBuckUIManager
          .getBuckTreeViewPanel()
          .getModifiableModel()
          .setNodeText(mProjectGenerationProgress, message);
    }
  }

  @Override
  public void consumeBuckProjectGenerationStarted(long timestamp) {
    mProjectGenerationStartTimestamp = timestamp;
    // start may be called before of after progress update
    if (BuckEventsConsumer.this.mProjectGenerationProgress == null) {
      BuckEventsConsumer.this.mProjectGenerationProgress =
          new BuckTextNode(
              "Current project generation progress: "
                  + Math.round(BuckEventsConsumer.this.mProjectGenerationProgressValue * 100)
                  + "%",
              TextType.INFO);

      mBuckUIManager
          .getBuckTreeViewPanel()
          .getModifiableModel()
          .addChild(mCurrentBuildRootElement, mProjectGenerationProgress);
    }
  }

  public void showTestResults() {
    float duration = (mTestingEndTimestamp - mTestingStartTimestamp) / 1000;

    final StringBuilder message =
        new StringBuilder("Testing ended, took " + duration + " seconds!\n");

    for (TestResults testResults : mTestResultsList) {
      for (TestCaseSummary testCaseSummary : testResults.getTestCases()) {
        long time = testCaseSummary.getTotalTime();
        String formattedTime;

        if (time >= 1000) {
          formattedTime = (time / 1000 + time % 1000) + "s";
        } else {
          formattedTime = time + "ms";
        }
        if (testCaseSummary.isSuccess() == true) {
          message.append(
              "PASS  "
                  + formattedTime
                  + "  "
                  + testCaseSummary.getTestResults().size()
                  + " passed  "
                  + testCaseSummary.getTestCaseName()
                  + "\n");
        } else {
          int failureCount = 0;
          StringBuilder failureMessage = new StringBuilder();
          for (TestResultsSummary testResultSummary : testCaseSummary.getTestResults()) {
            if (!testResultSummary.isSuccess()) {
              failureMessage.append(testResultSummary.getStacktrace() + "\n");
              failureCount++;
            }
          }

          message.append(
              "FAILED  "
                  + formattedTime
                  + "  "
                  + (testCaseSummary.getTestResults().size() - failureCount)
                  + " passed  "
                  + failureCount
                  + " failed  "
                  + testCaseSummary.getTestCaseName()
                  + "\n");
          message.append(failureMessage);
        }
      }
    }

    mBuckUIManager
        .getBuckTreeViewPanel()
        .getModifiableModel()
        .setNodeText(mTestResults, message.toString());
    mTestResultsList.clear();
  }

  @Override
  public void consumeTestResultsAvailable(long timestamp, final TestResults testResults) {
    if (mTestResults == null) {
      consumeTestRunStarted(timestamp);
    }
    mTestResultsList.add(testResults);
  }

  @Override
  public void consumeTestRunComplete(long timestamp) {
    if (mTestResults == null) {
      consumeTestRunStarted(timestamp);
    }
    mTestingEndTimestamp = timestamp;
    showTestResults();
  }

  @Override
  public void consumeTestRunStarted(long timestamp) {
    mTestingStartTimestamp = timestamp;
    // start may be called before of after progress update
    if (BuckEventsConsumer.this.mTestResults == null) {
      BuckEventsConsumer.this.mTestResults = new BuckTextNode("Running tests", TextType.INFO);
      mBuckUIManager
          .getBuckTreeViewPanel()
          .getModifiableModel()
          .addChild(mCurrentBuildRootElement, mTestResults);
    }
  }

  /**
   * Add a BuckTextNode to display a message as if it were sent as a console event
   *
   * @param message The string to be displayed
   * @param textType The type of the text
   */
  public void sendAsConsoleEvent(final String message, final TextType textType) {
    if (mCurrentBuildRootElement == null) {
      return;
    }

    mBuckUIManager
        .getBuckTreeViewPanel()
        .getModifiableModel()
        .addChild(mCurrentBuildRootElement, new BuckTextNode(message, textType));
  }

  @Override
  public void consumeConsoleEvent(final String message) {
    sendAsConsoleEvent(message, TextType.ERROR);
  }

  @Override
  public void consumeInstallFinished(long timestamp, final String packageName) {
    if (BuckInstallDebugAction.shouldDebug()) {
      ApplicationManager.getApplication()
          .executeOnPooledThread(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    String adbExecutable =
                        BuckExecutableSettingsProvider.getInstance(mProject).resolveAdbExecutable();
                    AndroidDebugger.init(adbExecutable);
                    AndroidDebugger.attachDebugger(packageName, mProject);
                    BuckInstallDebugAction.setDebug(false);
                  } catch (InterruptedException | RuntimeException e) {
                    // Display the error on our console
                    consumeConsoleEvent(e.toString());
                  }
                }
              });
    }
  }
}

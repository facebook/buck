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
import com.facebook.buck.intellij.ideabuck.debugger.AndroidDebugger;
import com.facebook.buck.intellij.ideabuck.fixup.BulkFileListenerDispatcher;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTreeNodeBuild;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTreeNodeDetail;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTreeNodeFileError;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTreeNodeTarget;
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
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.messages.MessageBusConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.tree.DefaultTreeModel;

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

  public BuckEventsConsumer(Project project) {
    mProject = project;
  }

  private String mTarget = null;
  private MessageBusConnection mConnection;
  private DefaultTreeModel mTreeModel;
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

  BuckTreeNodeBuild mCurrentBuildRootElement;
  BuckTreeNodeDetail mBuildProgress;
  BuckTreeNodeDetail mParseProgress;
  BuckTreeNodeDetail mTestResults;
  BuckTreeNodeDetail mProjectGenerationProgress;

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

  public void attach(String target, DefaultTreeModel treeModel) {
    mTreeModel = treeModel;
    mTarget = target == null ? "NONE" : target;
    mCurrentBuildRootElement = new BuckTreeNodeBuild(mTarget);

    ApplicationManager.getApplication()
        .invokeLater(
            new Runnable() {
              @Override
              public void run() {
                mTreeModel.setRoot(mCurrentBuildRootElement);
              }
            });

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

    mConnection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListenerDispatcher());

    attached = true;
  }

  @Override
  public void consumeBuckBuildProgressUpdate(long timestamp, double progressValue) {
    if (mBuildProgress == null) {
      consumeBuildStart(timestamp);
    }
    mBuildProgressValue = progressValue;
    final String message = "Current build progress: " + Math.round(mBuildProgressValue * 100) + "%";

    BuckEventsConsumer.this.mBuildProgress.setDetail(message);
    ApplicationManager.getApplication()
        .invokeLater(
            new Runnable() {
              @Override
              public void run() {
                BuckEventsConsumer.this.mTreeModel.reload();
              }
            });
  }

  @Override
  public void consumeParseRuleStart(long timestamp) {
    mParseFilesStartTimestamp = timestamp;

    // start may be called before of after progress update
    if (BuckEventsConsumer.this.mParseProgress == null) {
      BuckEventsConsumer.this.mParseProgress =
          new BuckTreeNodeDetail(
              mCurrentBuildRootElement,
              BuckTreeNodeDetail.DetailType.INFO,
              "Current file parsing progress: " + Math.round(mParseProgressValue * 100) + "%");
      BuckEventsConsumer.this.mCurrentBuildRootElement.addChild(mParseProgress);
      ApplicationManager.getApplication()
          .invokeLater(
              new Runnable() {
                @Override
                public void run() {
                  BuckEventsConsumer.this.mTreeModel.reload();
                }
              });
    }
  }

  @Override
  public void consumeParseRuleEnd(long timestamp) {
    consumeParseRuleProgressUpdate(timestamp, 1.0f);
    mParseFilesEndTimestamp = timestamp;
    float duration = (mParseFilesEndTimestamp - mParseFilesStartTimestamp) / 1000;
    final String message = "File parsing ended, took " + duration + " seconds!";

    BuckEventsConsumer.this.mParseProgress.setDetail(message);
    ApplicationManager.getApplication()
        .invokeLater(
            new Runnable() {
              @Override
              public void run() {
                BuckEventsConsumer.this.mTreeModel.reload();
              }
            });
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

      BuckEventsConsumer.this.mParseProgress.setDetail(message);
      ApplicationManager.getApplication()
          .invokeLater(
              new Runnable() {
                @Override
                public void run() {
                  BuckEventsConsumer.this.mTreeModel.reload();
                }
              });
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

  private BuckTreeNodeTarget buildTargetErrorNode(String target, List<String> errorMessages) {
    BuckTreeNodeTarget targetNode = new BuckTreeNodeTarget(mCurrentBuildRootElement, target);
    for (String currentError : errorMessages) {
      ErrorExtractor errorExtractor = new ErrorExtractor(currentError);
      ImmutableList<CompilerErrorItem> errorItems = errorExtractor.getErrors();

      BuckTreeNodeFileError currentFileErrorNode = null;
      String currentFilePath = "";

      for (CompilerErrorItem currentErrorItem : errorItems) {
        if (!currentErrorItem.getFilePath().equals(currentFilePath)) {
          if (currentFileErrorNode != null) {
            // Add to parent
            targetNode.addFileError(currentFileErrorNode);
          }
          // Create the new node, for the new file
          currentFileErrorNode =
              new BuckTreeNodeFileError(
                  targetNode, currentErrorItem.getFilePath(), currentErrorItem);
          currentFilePath = currentErrorItem.getFilePath();
        } else {
          currentFileErrorNode.addError(currentErrorItem);
        }
      }

      if (currentFileErrorNode != null) {
        // Add the leftovers
        targetNode.addFileError(currentFileErrorNode);
      }
    }
    return targetNode;
  }

  private void displayErrors() {
    if (mErrors.size() > 0) {
      if (!BuckToolWindowFactory.isToolWindowVisible(mProject)) {
        BuckToolWindowFactory.showToolWindow(mProject);
      }
      Set<String> targetsWithErrors = mErrors.keySet();
      for (String target : targetsWithErrors) {
        List<String> errorMessages = mErrors.get(target);
        if (errorMessages.size() > 0) {
          BuckTreeNodeTarget targetNode = buildTargetErrorNode(target, errorMessages);
          mCurrentBuildRootElement.addChild(targetNode);
        }
      }
    }
  }

  public void clearDisplay() {
    if (mTestResults != null) {
      mCurrentBuildRootElement.removeChild(mTestResults);
    }
    if (mParseProgress != null) {
      mCurrentBuildRootElement.removeChild(mParseProgress);
    }
    if (mBuildProgress != null) {
      mCurrentBuildRootElement.removeChild(mBuildProgress);
    }

    if (mProjectGenerationProgress != null) {
      mCurrentBuildRootElement.removeChild(mProjectGenerationProgress);
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

    //set progress to 100%
    consumeBuckBuildProgressUpdate(timestamp, 1f);

    if (errorsMessageToUse.length() > 0) {
      clearDisplay();
      BuckTreeNodeDetail errorsMessageNode =
          new BuckTreeNodeDetail(
              BuckEventsConsumer.this.mCurrentBuildRootElement,
              BuckTreeNodeDetail.DetailType.ERROR,
              errorsMessageToUse);
      BuckEventsConsumer.this.mCurrentBuildRootElement.addChild(errorsMessageNode);

      // Display errors
      BuckEventsConsumer.this.displayErrors();
    }

    BuckEventsConsumer.this.mBuildProgress.setDetail(message);
    ApplicationManager.getApplication()
        .invokeLater(
            new Runnable() {
              @Override
              public void run() {
                BuckEventsConsumer.this.mTreeModel.reload();
              }
            });
  }

  @Override
  public void consumeBuildStart(long timestamp) {
    mMainBuildStartTimestamp = timestamp;
    if (BuckEventsConsumer.this.mBuildProgress == null) {
      BuckEventsConsumer.this.mBuildProgress =
          new BuckTreeNodeDetail(
              BuckEventsConsumer.this.mCurrentBuildRootElement,
              BuckTreeNodeDetail.DetailType.INFO,
              "Current build progress: " + Math.round(mBuildProgressValue * 100) + "%");
      // start may be called before of after progress update
      BuckEventsConsumer.this.mCurrentBuildRootElement.addChild(mBuildProgress);
      ApplicationManager.getApplication()
          .invokeLater(
              new Runnable() {
                @Override
                public void run() {
                  BuckEventsConsumer.this.mTreeModel.reload();
                }
              });
    }
  }

  @Override
  public void consumeBuckProjectGenerationFinished(long timestamp) {
    consumeBuckProjectGenerationProgress(timestamp, 1.0f);
    mProjectGenerationFinishedTimestamp = timestamp;
    float duration =
        (mProjectGenerationFinishedTimestamp - mProjectGenerationStartTimestamp) / 1000;
    final String message = "Project generation ended, took " + duration + " seconds!";

    BuckEventsConsumer.this.mProjectGenerationProgress.setDetail(message);
    ApplicationManager.getApplication()
        .invokeLater(
            new Runnable() {
              @Override
              public void run() {
                BuckEventsConsumer.this.mTreeModel.reload();
                // IntelliJ's synchronize action
                FileDocumentManager.getInstance().saveAllDocuments();
                VirtualFileManager.getInstance().refreshWithoutFileWatcher(true);
              }
            });
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

      BuckEventsConsumer.this.mProjectGenerationProgress.setDetail(message);
      ApplicationManager.getApplication()
          .invokeLater(
              new Runnable() {
                @Override
                public void run() {
                  BuckEventsConsumer.this.mTreeModel.reload();
                }
              });
    }
  }

  @Override
  public void consumeBuckProjectGenerationStarted(long timestamp) {
    mProjectGenerationStartTimestamp = timestamp;
    // start may be called before of after progress update
    if (BuckEventsConsumer.this.mProjectGenerationProgress == null) {
      BuckEventsConsumer.this.mProjectGenerationProgress =
          new BuckTreeNodeDetail(
              BuckEventsConsumer.this.mCurrentBuildRootElement,
              BuckTreeNodeDetail.DetailType.INFO,
              "Current project generation progress: "
                  + Math.round(BuckEventsConsumer.this.mProjectGenerationProgressValue * 100)
                  + "%");

      BuckEventsConsumer.this.mCurrentBuildRootElement.addChild(mProjectGenerationProgress);
      ApplicationManager.getApplication()
          .invokeLater(
              new Runnable() {
                @Override
                public void run() {
                  BuckEventsConsumer.this.mTreeModel.reload();
                }
              });
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
    BuckEventsConsumer.this.mTestResults.setDetail(message.toString());
    ApplicationManager.getApplication()
        .invokeLater(
            new Runnable() {
              @Override
              public void run() {
                BuckEventsConsumer.this.mTreeModel.reload();
              }
            });
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
      BuckEventsConsumer.this.mTestResults =
          new BuckTreeNodeDetail(
              mCurrentBuildRootElement, BuckTreeNodeDetail.DetailType.INFO, "Running tests");
      BuckEventsConsumer.this.mCurrentBuildRootElement.addChild(mTestResults);

      ApplicationManager.getApplication()
          .invokeLater(
              new Runnable() {
                @Override
                public void run() {
                  BuckEventsConsumer.this.mTreeModel.reload();
                }
              });
    }
  }

  @Override
  public void consumeConsoleEvent(final String message) {
    if (!BuckToolWindowFactory.isToolWindowInstantiated(mProject)) {
      return;
    }
    if (!BuckToolWindowFactory.isToolWindowVisible(mProject)) {
      BuckToolWindowFactory.showToolWindow(mProject);
    }

    if (mCurrentBuildRootElement == null) {
      return;
    }
    mCurrentBuildRootElement.addChild(
        new BuckTreeNodeDetail(
            mCurrentBuildRootElement, BuckTreeNodeDetail.DetailType.ERROR, message));

    ApplicationManager.getApplication()
        .invokeLater(
            new Runnable() {
              @Override
              public void run() {
                BuckEventsConsumer.this.mTreeModel.reload();
              }
            });
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
                    AndroidDebugger.init();
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

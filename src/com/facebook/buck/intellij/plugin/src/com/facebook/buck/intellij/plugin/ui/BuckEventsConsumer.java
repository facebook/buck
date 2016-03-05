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

package com.facebook.buck.intellij.plugin.ui;

import com.facebook.buck.intellij.plugin.ui.tree.BuckTreeNodeBuild;
import com.facebook.buck.intellij.plugin.ui.tree.BuckTreeNodeDetail;
import com.facebook.buck.intellij.plugin.ui.tree.BuckTreeNodeFileError;
import com.facebook.buck.intellij.plugin.ui.tree.BuckTreeNodeTarget;
import com.facebook.buck.intellij.plugin.ui.utils.CompilerErrorItem;
import com.facebook.buck.intellij.plugin.ui.utils.ErrorExtractor;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckBuildEndConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckBuildStartConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.CompilerErrorConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckBuildProgressUpdateConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.RulesParsingStartConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.RulesParsingEndConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.RulesParsingProgressUpdateConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.TestRunStartedConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.TestResultsAvailableConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.TestRunCompleteConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.parts.TestCaseSummary;
import com.facebook.buck.intellij.plugin.ws.buckevents.parts.TestResults;
import com.facebook.buck.intellij.plugin.ws.buckevents.parts.TestResultsSummary;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;

import javax.swing.tree.DefaultTreeModel;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;

public class BuckEventsConsumer implements
        BuckBuildStartConsumer,
        BuckBuildEndConsumer,
        BuckBuildProgressUpdateConsumer,
        RulesParsingStartConsumer,
        RulesParsingEndConsumer,
        RulesParsingProgressUpdateConsumer,
        TestRunStartedConsumer,
        TestRunCompleteConsumer,
        TestResultsAvailableConsumer,

        CompilerErrorConsumer {

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
    private long mMainBuildStartTimestamp = 0;
    private long mMainBuildEndTimestamp = 0;
    private long mParseFilesStartTimestamp = 0;
    private long mParseFilesEndTimestamp = 0;
    private long mTestingEndTimestamp = 0;
    private long mTestingStartTimestamp = 0;
    private List<TestResults> mTestResultsList =
            Collections.synchronizedList(new LinkedList<TestResults>());


    BuckTreeNodeBuild mCurrentBuildRootElement;
    BuckTreeNodeDetail mBuildProgress;
    BuckTreeNodeDetail mParseProgress;
    BuckTreeNodeDetail mTestResults;

    public void detach() {
        mConnection.disconnect();
    }

    public void detachWithMessage(String message) {
        detach();
        BuckEventsConsumer.this.mTreeModel.setRoot(new BuckTreeNodeDetail(
                null,
                BuckTreeNodeDetail.DetailType.ERROR,
                "Disconnected from buck: " + message
        ));
        BuckEventsConsumer.this.mTreeModel.reload();
    }

    public void attach(String target, DefaultTreeModel treeModel) {
        mTreeModel = treeModel;
        mTarget = target;
        mCurrentBuildRootElement = new BuckTreeNodeBuild(mTarget);

        mTestResults = new BuckTreeNodeDetail(
                mCurrentBuildRootElement,
                BuckTreeNodeDetail.DetailType.INFO,
                "Running tests");
        mBuildProgress = new BuckTreeNodeDetail(
                mCurrentBuildRootElement,
                BuckTreeNodeDetail.DetailType.INFO,
                "Current build progress: " + Math.round(mBuildProgressValue * 100) + "%");
        mParseProgress = new BuckTreeNodeDetail(
                mCurrentBuildRootElement,
                BuckTreeNodeDetail.DetailType.INFO,
                "Current file parsing progress: " + Math.round(mParseProgressValue * 100) + "%");

        mCurrentBuildRootElement.addChild(mParseProgress);
        mCurrentBuildRootElement.addChild(mBuildProgress);

        mTreeModel.setRoot(mCurrentBuildRootElement);

        mMainBuildStartTimestamp = 0;

        mConnection = mProject.getMessageBus().connect();

        mConnection.subscribe(BuckBuildStartConsumer.BUCK_BUILD_START, this);
        mConnection.subscribe(BuckBuildEndConsumer.BUCK_BUILD_END, this);
        mConnection.subscribe(BuckBuildProgressUpdateConsumer.BUCK_BUILD_PROGRESS_UPDATE, this);

        mConnection.subscribe(RulesParsingStartConsumer.BUCK_PARSE_RULE_START, this);
        mConnection.subscribe(RulesParsingEndConsumer.BUCK_PARSE_RULE_END, this);
        mConnection.subscribe(RulesParsingProgressUpdateConsumer.BUCK_PARSE_PROGRESS_UPDATE, this);

        mConnection.subscribe(CompilerErrorConsumer.COMPILER_ERROR_CONSUMER, this);

        mConnection.subscribe(TestRunStartedConsumer.BUCK_TEST_RUN_STARTED, this);
        mConnection.subscribe(TestRunCompleteConsumer.BUCK_TEST_RUN_COMPLETE, this);
        mConnection.subscribe(TestResultsAvailableConsumer.BUCK_TEST_RESULTS_AVAILABLE, this);
    }
    @Override
    public void consumeBuckBuildProgressUpdate(long timestamp,
                                              double progressValue) {

        mBuildProgressValue = progressValue;
        final String message = "Current build progress: " +
            Math.round(mBuildProgressValue * 100) + "%";

        ApplicationManager.getApplication().invokeLater(new Runnable() {
          @Override
          public void run() {
            BuckEventsConsumer.this.mBuildProgress.setDetail(message);
            BuckEventsConsumer.this.mTreeModel.reload();
          }
        });
    }

    @Override
    public void consumeParseRuleStart(long timestamp) {
        mParseFilesStartTimestamp = timestamp;
    }

    @Override
    public void consumeParseRuleEnd(long timestamp) {
        consumeParseRuleProgressUpdate(timestamp, 1.0f);
        mParseFilesEndTimestamp = timestamp;
        float duration = (mParseFilesEndTimestamp - mParseFilesStartTimestamp) / 1000;
        final String message = "File parsing ended, took " + duration + " seconds!";

        ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
                BuckEventsConsumer.this.mParseProgress.setDetail(message);
                BuckEventsConsumer.this.mTreeModel.reload();
            }
        });
    }

    @Override
    public void consumeParseRuleProgressUpdate(long timestamp,
                                               double progressValue) {
        if (mParseProgressValue != 1.0f) {
            mParseProgressValue = progressValue;
            final String message = "Current file parsing progress: " +
                Math.round(mParseProgressValue * 100) + "%";

            ApplicationManager.getApplication().invokeLater(new Runnable() {
                @Override
                public void run() {
                    BuckEventsConsumer.this.mParseProgress.setDetail(message);
                    BuckEventsConsumer.this.mTreeModel.reload();
                }
            });
        }
    }

    @Override
    public void consumeCompilerError(String target,
                                     long timestamp,
                                     String error,
                                     ImmutableSet<String> suggestions) {
        List<String> existingErrors;
        if (mErrors.containsKey(target)) {
            existingErrors = mErrors.get(target);
        } else {
            existingErrors = new ArrayList<String>();
        }
        existingErrors.add(error);
        mErrors.put(target, existingErrors);
    }

    private void log(String message) {
        BuckToolWindowFactory.outputConsoleMessage(
                mProject,
                message,
                ConsoleViewContentType.NORMAL_OUTPUT
        );
    }

    private BuckTreeNodeTarget buildTargetErrorNode(String target, List<String> errorMessages) {
        BuckTreeNodeTarget targetNode = new BuckTreeNodeTarget(mCurrentBuildRootElement, target);
        for (String currentError: errorMessages) {
            ErrorExtractor errorExtractor = new ErrorExtractor(currentError);
            ImmutableList<CompilerErrorItem> errorItems = errorExtractor.getErrors();

            BuckTreeNodeFileError currentFileErrorNode = null;
            String currentFilePath = "";

            for (CompilerErrorItem currentErrorItem: errorItems) {
                if (!currentErrorItem.getFilePath().equals(currentFilePath)) {
                    if (currentFileErrorNode != null) {
                        // Add to parent
                        targetNode.addFileError(currentFileErrorNode);
                    }
                    // Create the new node, for the new file
                    currentFileErrorNode = new BuckTreeNodeFileError(
                            targetNode,
                            currentErrorItem.getFilePath(),
                            currentErrorItem);
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
            Set<String> targetsWithErrors = mErrors.keySet();
            for (String target: targetsWithErrors) {
                List<String> errorMessages = mErrors.get(target);
                if (errorMessages.size() > 0) {
                    BuckTreeNodeTarget targetNode = buildTargetErrorNode(target, errorMessages);
                    mCurrentBuildRootElement.addChild(targetNode);
                }
            }
        }
    }

    public void clearDisplay() {
        BuckEventsConsumer.this.mCurrentBuildRootElement.removeChild(mParseProgress);
        BuckEventsConsumer.this.mCurrentBuildRootElement.removeChild(mBuildProgress);
        if (mTestResults != null) {
            BuckEventsConsumer.this.mCurrentBuildRootElement.removeChild(mTestResults);
        }
    }

    @Override
    public void consumeBuildEnd(final long timestamp) {
        mMainBuildEndTimestamp = timestamp;
        float duration = (mMainBuildEndTimestamp - mMainBuildStartTimestamp) / 1000;
        final String message = "Build ended, took " + duration + " seconds!";

        int errors = 0;
        int warnings = 0;

        for (String cTarget: mErrors.keySet()) {
            List<String> currentErrorMessages = mErrors.get(cTarget);
            for (String currentErrorMessage: currentErrorMessages) {
                ErrorExtractor errorExtractor = new ErrorExtractor(currentErrorMessage);
                for (CompilerErrorItem currentErrorItem: errorExtractor.getErrors()) {
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

        ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {

                //set progress to 100%
                consumeBuckBuildProgressUpdate(timestamp, 1f);

                ApplicationManager.getApplication().invokeLater(new Runnable() {
                  @Override
                  public void run() {
                    BuckEventsConsumer.this.mBuildProgress.setDetail(message);
                    BuckEventsConsumer.this.mTreeModel.reload();
                  }
                });

                if (errorsMessageToUse.length() > 0) {
                    clearDisplay();
                    BuckTreeNodeDetail errorsMessageNode = new BuckTreeNodeDetail(
                            BuckEventsConsumer.this.mCurrentBuildRootElement,
                            BuckTreeNodeDetail.DetailType.ERROR,
                            errorsMessageToUse
                    );
                    BuckEventsConsumer.this.mCurrentBuildRootElement.addChild(
                            errorsMessageNode
                    );

                    // Display errors
                    BuckEventsConsumer.this.displayErrors();
                }

                BuckEventsConsumer.this.mTreeModel.reload();
            }
        });

        log("Ending build\n");
    }

    @Override
    public void consumeBuildStart(long timestamp) {
        log("Starting build\n");
        mMainBuildStartTimestamp = timestamp;
    }

    public void showTestResults() {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
                float duration = (mTestingEndTimestamp - mTestingStartTimestamp) / 1000;

                StringBuilder message = new StringBuilder(
                        "Testing ended, took " + duration + " seconds!\n");

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
                            message.append("PASS  " + formattedTime + "  " +
                                    testCaseSummary.getTestResults().size() +
                                    " passed  " + testCaseSummary.getTestCaseName() + "\n");
                        } else {
                            int failureCount = 0;
                            StringBuilder failureMessage = new StringBuilder();
                            for (TestResultsSummary testResultSummary :
                                    testCaseSummary.getTestResults()) {
                                if (!testResultSummary.isSuccess()) {
                                    failureMessage.append(testResultSummary.getStacktrace() + "\n");
                                    failureCount++;
                                }
                            }

                            message.append("FAILED  " + formattedTime + "  " +
                                    (testCaseSummary.getTestResults().size() - failureCount) +
                                    " passed  " + failureCount + " failed  " +
                                    testCaseSummary.getTestCaseName() + "\n");
                            message.append(failureMessage);
                        }
                    }
                }
                BuckEventsConsumer.this.mTestResults.setDetail(message.toString());
                BuckEventsConsumer.this.mTreeModel.reload();
                mTestResultsList.clear();
            }
        });
    }

    @Override
    public void consumeTestResultsAvailable(long timestamp, final TestResults testResults) {
        mTestResultsList.add(testResults);
    }

    @Override
    public void consumeTestRunComplete(long timestamp) {
        mTestingEndTimestamp = timestamp;
        showTestResults();
    }

    @Override
    public void consumeTestRunStarted(long timestamp) {
        mTestingStartTimestamp = timestamp;
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
                mCurrentBuildRootElement.removeChild(mTestResults);
                mCurrentBuildRootElement.addChild(mTestResults);
                mTreeModel.reload();
            }
        });
    }
}

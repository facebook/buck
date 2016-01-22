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

import com.facebook.buck.intellij.plugin.config.BuildRuleItem;
import com.facebook.buck.intellij.plugin.ui.tree.BuckTreeNodeBuild;
import com.facebook.buck.intellij.plugin.ui.tree.BuckTreeNodeDetail;
import com.facebook.buck.intellij.plugin.ui.tree.BuckTreeNodeFileError;
import com.facebook.buck.intellij.plugin.ui.tree.BuckTreeNodeTarget;
import com.facebook.buck.intellij.plugin.ui.utils.CompilerErrorItem;
import com.facebook.buck.intellij.plugin.ui.utils.ErrorExtractor;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckBuildEndConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckBuildStartConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckInternalBatchCommitConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckInternalBatchStartConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuildRuleEndConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuildRuleFailureConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuildRuleStartConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuildRuleSuspendedConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.CompilerErrorConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckBuildProgressUpdateConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.RulesParsingStartConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.RulesParsingEndConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.RulesParsingProgressUpdateConsumer;
import com.google.common.collect.ImmutableList;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;

import javax.swing.tree.DefaultTreeModel;
import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

public class BuckEventsConsumer implements
        BuckBuildStartConsumer,
        BuckBuildEndConsumer,
        BuckBuildProgressUpdateConsumer,
        RulesParsingStartConsumer,
        RulesParsingEndConsumer,
        RulesParsingProgressUpdateConsumer,

        BuildRuleStartConsumer,
        BuildRuleEndConsumer,
        BuildRuleFailureConsumer,
        BuildRuleSuspendedConsumer,
        CompilerErrorConsumer,

        BuckInternalBatchStartConsumer,
        BuckInternalBatchCommitConsumer {

    private Project mProject;
    public BuckEventsConsumer(Project project) {
        mProject = project;
    }
    private String mTarget = null;
    private MessageBusConnection mConnection;
    private DefaultTreeModel mTreeModel;
    private Map<String, BuildRuleItem> mRules =
            Collections.synchronizedMap(new HashMap<String, BuildRuleItem>());
    private Map<String, List<String>> mErrors =
            Collections.synchronizedMap(new HashMap<String, List<String>>());
    private float mBuildProgressValue = 0;
    private float mParseProgressValue = 0;
    private BigInteger mMainBuildStartTimestamp = BigInteger.ZERO;
    private BigInteger mMainBuildEndTimestamp = BigInteger.ZERO;
    private BigInteger mParseFilesStartTimestamp = BigInteger.ZERO;
    private BigInteger mParseFilesEndTimestamp = BigInteger.ZERO;

    BuckTreeNodeBuild mCurrentBuildRootElement;
    BuckTreeNodeDetail mBuildProgress;
    BuckTreeNodeDetail mParseProgress;
    BuckTreeNodeDetail mRunningTasks;
    BuckTreeNodeDetail mSuspendedTasks;
    BuckTreeNodeDetail mFinishedTasks;


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

        mBuildProgress = new BuckTreeNodeDetail(
                mCurrentBuildRootElement,
                BuckTreeNodeDetail.DetailType.INFO,
                "Current build progress: " + Math.round(mBuildProgressValue * 100) + "%");
        mParseProgress = new BuckTreeNodeDetail(
                mCurrentBuildRootElement,
                BuckTreeNodeDetail.DetailType.INFO,
                "Current file parsing progress: " + Math.round(mParseProgressValue * 100) + "%");
        mRunningTasks = new BuckTreeNodeDetail(
                mCurrentBuildRootElement,
                BuckTreeNodeDetail.DetailType.INFO,
                "Running tasks: 0"
        );
        mSuspendedTasks = new BuckTreeNodeDetail(
                mCurrentBuildRootElement,
                BuckTreeNodeDetail.DetailType.INFO,
                "Suspended tasks: 0"
        );
        mFinishedTasks = new BuckTreeNodeDetail(
                mCurrentBuildRootElement,
                BuckTreeNodeDetail.DetailType.INFO,
                "Finished tasks: 0"
        );
        mCurrentBuildRootElement.addChild(mParseProgress);
        mCurrentBuildRootElement.addChild(mBuildProgress);
        mCurrentBuildRootElement.addChild(mRunningTasks);
        mCurrentBuildRootElement.addChild(mSuspendedTasks);
        mCurrentBuildRootElement.addChild(mFinishedTasks);

        mTreeModel.setRoot(mCurrentBuildRootElement);

        mMainBuildStartTimestamp = BigInteger.ZERO;

        mConnection = mProject.getMessageBus().connect();

        mConnection.subscribe(BuckBuildStartConsumer.BUCK_BUILD_START, this);
        mConnection.subscribe(BuckBuildEndConsumer.BUCK_BUILD_END, this);
        mConnection.subscribe(BuckBuildProgressUpdateConsumer.BUCK_BUILD_PROGRESS_UPDATE, this);

        mConnection.subscribe(BuildRuleStartConsumer.BUCK_BUILD_RULE_START, this);
        mConnection.subscribe(BuildRuleEndConsumer.BUCK_BUILD_RULE_END, this);
        mConnection.subscribe(BuildRuleSuspendedConsumer.BUCK_BUILD_RULE_SUSPENDED, this);

        mConnection.subscribe(RulesParsingStartConsumer.BUCK_PARSE_RULE_START, this);
        mConnection.subscribe(RulesParsingEndConsumer.BUCK_PARSE_RULE_END, this);
        mConnection.subscribe(RulesParsingProgressUpdateConsumer.BUCK_PARSE_PROGRESS_UPDATE, this);

        mConnection.subscribe(CompilerErrorConsumer.COMPILER_ERROR_CONSUMER, this);

        mConnection.subscribe(BuckInternalBatchStartConsumer.BUCK_INTERNAL_BATCH_START, this);
        mConnection.subscribe(BuckInternalBatchCommitConsumer.BUCK_INTERNAL_BATCH_COMMIT, this);
    }

    @Override
    public void consumeBuildRuleEnd(String build,
                                    String ruleKeySafe,
                                    String target,
                                    BigInteger timestamp,
                                    boolean mainRule, String status) {
        if (mRules.containsKey(target)) {
            BuildRuleItem bri = mRules.get(target);
            bri.endTimestamp = timestamp;
            if (bri.status == BuildRuleItem.Status.RUNNING) {
                bri.status = BuildRuleItem.Status.FINISHED;
                mRules.put(target, bri);
            }
            if (!mBatchMode) {
                refreshTasksStatus();
            }
        }
    }

    @Override
    public void consumeBuildRuleStart(String build,
                                      String ruleKeySafe,
                                      String target,
                                      BigInteger timestamp,
                                      boolean mainRule) {
        BuildRuleItem bri = new BuildRuleItem();
        bri.name = target;
        bri.startTimestamp = timestamp;
        bri.status = BuildRuleItem.Status.RUNNING;
        mRules.put(target, bri);
        if (!mBatchMode) {
            refreshTasksStatus();
        }
    }

    @Override
    public void consumeBuildRuleFailure(String build,
                                        String ruleKeySafe,
                                        String target,
                                        BigInteger timestamp,
                                        String message) {
        // TODO
    }

    @Override
    public void consumeBuckBuildProgressUpdate(String build,
                                              BigInteger timestamp,
                                              float progressValue) {

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
    public void consumeParseRuleStart(String build, BigInteger timestamp) {
        mParseFilesStartTimestamp = timestamp;
    }

    @Override
    public void consumeParseRuleEnd(String build, BigInteger timestamp) {
        consumeParseRuleProgressUpdate(build, timestamp, 1.0f);
        mParseFilesEndTimestamp = timestamp;
        float duration = mParseFilesEndTimestamp.
            subtract(mParseFilesStartTimestamp).floatValue() / 1000;
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
    public void consumeParseRuleProgressUpdate(String build,
                                               BigInteger timestamp,
                                               float progressValue) {
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
    public void consumeBuildRuleSuspended(String build,
                                          String ruleKeySafe,
                                          String target,
                                          BigInteger timestamp,
                                          String buildRuleType) {
        if (mRules.containsKey(target)) {
            BuildRuleItem bri = mRules.get(target);
            bri.status = BuildRuleItem.Status.SUSPENDED;
            mRules.put(target, bri);
            if (!mBatchMode) {
                refreshTasksStatus();
            }
        }
    }

    @Override
    public void consumeCompilerError(String build,
                                     String target,
                                     BigInteger timestamp,
                                     String error,
                                     String[] suggestions) {
        List<String> existingErrors;
        if (mErrors.containsKey(target)) {
            existingErrors = mErrors.get(target);
        } else {
            existingErrors = new ArrayList<String>();
        }
        existingErrors.add(error);
        mErrors.put(target, existingErrors);
    }

    private void refreshTasksStatus() {
        int running = 0;
        int suspended = 0;
        int finished = 0;

        for (String cTarget: mRules.keySet()) {
            BuildRuleItem bri = mRules.get(cTarget);
            if (bri.status == BuildRuleItem.Status.RUNNING) {
                running++;
            } else if (bri.status == BuildRuleItem.Status.SUSPENDED) {
                suspended++;
            } else if (bri.status == BuildRuleItem.Status.FINISHED ||
                    bri.status == BuildRuleItem.Status.ERROR) {
                finished++;
            }
        }
        final String finishedMessage = "Finished tasks: " + finished;
        final String runningMessage = "Running tasks: " + running;
        final String suspendedMessage = "Suspended tasks: " + suspended;

        ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
                BuckEventsConsumer.this.mFinishedTasks.setDetail(finishedMessage);
                BuckEventsConsumer.this.mRunningTasks.setDetail(runningMessage);
                BuckEventsConsumer.this.mSuspendedTasks.setDetail(suspendedMessage);
                BuckEventsConsumer.this.mTreeModel.reload();
            }
        });
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
        BuckEventsConsumer.this.mCurrentBuildRootElement.removeChild(mFinishedTasks);
        BuckEventsConsumer.this.mCurrentBuildRootElement.removeChild(mRunningTasks);
        BuckEventsConsumer.this.mCurrentBuildRootElement.removeChild(mSuspendedTasks);
    }

    @Override
    public void consumeBuildEnd(final String build, final BigInteger timestamp) {
        mMainBuildEndTimestamp = timestamp;
        float duration = mMainBuildEndTimestamp.
                subtract(mMainBuildStartTimestamp).floatValue() / 1000;
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
                consumeBuckBuildProgressUpdate(build, timestamp, 1f);

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

        mConnection.disconnect();
        log("Ending build\n");
    }

    @Override
    public void consumeBuildStart(String build, BigInteger timestamp) {
        log("Starting build\n");
        mMainBuildStartTimestamp = timestamp;
    }

    private boolean mBatchMode = false;
    @Override
    public void startBatch() {
        mBatchMode = true;
    }

    @Override
    public void commitBatch() {
        mBatchMode = false;
        refreshTasksStatus();
    }
}

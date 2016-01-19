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

package com.facebook.buck.intellij.plugin.ws.buckevents.consumers;

import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBus;

public class BuckEventsConsumerFactory {

    private MessageBus mBus;
    public BuckEventsConsumerFactory(Project project) {
        mBus = project.getMessageBus();
    }

    public BuildRuleStartConsumer getBuildRuleStartConsumer() {
        return mBus.syncPublisher(BuildRuleStartConsumer.BUCK_BUILD_RULE_START);
    }
    public BuildRuleEndConsumer getBuildRuleEndConsumer() {
        return mBus.syncPublisher(BuildRuleEndConsumer.BUCK_BUILD_RULE_END);
    }

    public RulesParsingStartConsumer getRulesParsingStartConsumer() {
        return mBus.syncPublisher(RulesParsingStartConsumer.BUCK_PARSE_RULE_START);
    }

    public RulesParsingEndConsumer getRulesParsingEndConsumer() {
        return mBus.syncPublisher(RulesParsingEndConsumer.BUCK_PARSE_RULE_END);
    }

    public RulesParsingProgressUpdateConsumer getRulesParsingProgressUpdateConsumer() {
        return mBus.syncPublisher(RulesParsingProgressUpdateConsumer.BUCK_PARSE_PROGRESS_UPDATE);
    }

    public BuckBuildProgressUpdateConsumer getBuckBuildProgressUpdateConsumer() {
        return mBus.syncPublisher(BuckBuildProgressUpdateConsumer.BUCK_BUILD_PROGRESS_UPDATE);
    }

    public BuildRuleSuspendedConsumer getBuildRuleSuspendedConsumer() {
        return mBus.syncPublisher(BuildRuleSuspendedConsumer.BUCK_BUILD_RULE_SUSPENDED);
    }

    public CompilerErrorConsumer getCompilerErrorConsumer() {
        return mBus.syncPublisher(CompilerErrorConsumer.COMPILER_ERROR_CONSUMER);
    }

    public BuckBuildStartConsumer getBuildStartConsumer() {
        return mBus.syncPublisher(BuckBuildStartConsumer.BUCK_BUILD_START);
    }

    public BuckBuildEndConsumer getBuildEndConsumer() {
        return mBus.syncPublisher(BuckBuildEndConsumer.BUCK_BUILD_END);
    }

    public BuckInternalBatchStartConsumer getBatchStartConsumer() {
        return mBus.syncPublisher(BuckInternalBatchStartConsumer.BUCK_INTERNAL_BATCH_START);
    }
    public BuckInternalBatchCommitConsumer getBatchCommitConsumer() {
        return mBus.syncPublisher(BuckInternalBatchCommitConsumer.BUCK_INTERNAL_BATCH_COMMIT);
    }
}

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

import com.intellij.util.messages.Topic;

import java.math.BigInteger;

/**
 * Created by petrumarius on 9/22/15.
 */
public interface BuildRuleEndConsumer {
    Topic<BuildRuleEndConsumer> BUCK_BUILD_RULE_END = Topic.create(
            "buck.build-rule.end",
            BuildRuleEndConsumer.class
    );
    void consumeBuildRuleEnd(String build,
                             String ruleKeySafe,
                             String target,
                             BigInteger timestamp,
                             boolean mainRule,
                             String status);
}

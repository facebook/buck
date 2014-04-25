/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

public class BuckBuildLogTest {

  @Test
  public void testBuildLogParsing() {
    ImmutableList<String> buildLogLines = ImmutableList.of(
        "735 INFO  BuildRuleFinished(//example/base:one): " +
            "SUCCESS MISS BUILT_LOCALLY 489e1b85f804dc0f66545f2ce06f57ee85204747",
        "735 INFO  BuildRuleFinished(//example/base:two): " +
            "FAIL MISS MISSING 489e1b85f804dc0f66545f2ce06f57ee85204747",
        "735 INFO  BuildRuleFinished(//example/base:three): " +
            "SUCCESS SKIP MATCHING_RULE_KEY 489e1b85f804dc0f66545f2ce06f57ee85204747");

    BuckBuildLog buildLog = BuckBuildLog.fromLogContents(buildLogLines);
    buildLog.assertTargetBuiltLocally("//example/base:one");
    buildLog.assertTargetFailed("//example/base:two");
    buildLog.assertTargetHadMatchingRuleKey("//example/base:three");
  }
}

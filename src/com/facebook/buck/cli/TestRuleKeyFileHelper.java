/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.WriteFileStep;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helper class to annotate and validate an output directory of a test with a rule key.
 */
public class TestRuleKeyFileHelper {

  public static final String RULE_KEY_FILE = ".rulekey";

  private final BuildEngine buildEngine;

  public TestRuleKeyFileHelper(BuildEngine buildEngine) {
    this.buildEngine = buildEngine;
  }

  /**
   * Creates a file in the test's output directory and writes the rule key file in it.
   * @return A {@link Step} that writes the rule key for the test to it's output directory
   */
  public Step createRuleKeyInDirStep(TestRule testRule) throws IOException {
    RuleKey ruleKey = buildEngine.getRuleKey(testRule.getBuildTarget());
    return new WriteFileStep(
        ((BuildRule) testRule).getProjectFilesystem(),
        ruleKey.toString(),
        getRuleKeyFilePath(testRule),
        /* executable */ false);
  }

  /**
   * Checks if a matching rule key file for a test is present in its directoryReturns
   * @return true if a rule key is written in the specified directory.
   */
  public boolean isRuleKeyInDir(TestRule testRule) throws IOException {
    ProjectFilesystem filesystem = ((BuildRule) testRule).getProjectFilesystem();
    Path ruleKeyPath = filesystem.resolve(getRuleKeyFilePath(testRule));
    if (!Files.isRegularFile(ruleKeyPath)) {
      return false;
    }

    RuleKey ruleKey = buildEngine.getRuleKey(testRule.getBuildTarget());
    try (BufferedReader reader = Files.newBufferedReader(ruleKeyPath, UTF_8)) {
      return reader.readLine().equals(ruleKey.toString());
    }
  }

  /**
   * Get the path file where the rule key is written, given the path to the output directory.
   */
  private Path getRuleKeyFilePath(TestRule testRule) {
    return testRule.getPathToTestOutputDirectory().resolve(RULE_KEY_FILE);
  }
}

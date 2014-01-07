/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultFileHashCache;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.ProjectFilesystem;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * Unit test for {@link RuleKey}.
 */
public class RuleKeyTest {

  @Test
  public void testRuleKeyFromHashString() {
    RuleKey ruleKey = new RuleKey("19d2558a6bd3a34fb3f95412de9da27ed32fe208");
    assertEquals("19d2558a6bd3a34fb3f95412de9da27ed32fe208", ruleKey.toString());
  }

  /**
   * Ensure that build rules with the same inputs but different deps have unique RuleKeys.
   */
  @Test
  public void testRuleKeyDependsOnDeps() throws IOException {
    BuildRuleResolver ruleResolver1 = new BuildRuleResolver();
    BuildRuleResolver ruleResolver2 = new BuildRuleResolver();
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(new File("."));
    final FileHashCache fileHashCache = new DefaultFileHashCache(projectFilesystem,
        new TestConsole());
    AbstractBuildRuleBuilderParams builderParams = new DefaultBuildRuleBuilderParams(
        projectFilesystem,
        new RuleKeyBuilderFactory() {
          @Override
          public Builder newInstance(BuildRule buildRule) {
            return RuleKey.builder(buildRule, fileHashCache);
          }
        });

    // Create a dependent build rule, //src/com/facebook/buck/cli:common.
    DefaultJavaLibraryRule.Builder commonJavaLibraryRuleBuilder = DefaultJavaLibraryRule
        .newJavaLibraryRuleBuilder(builderParams)
        .setBuildTarget(BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:common"));
    ruleResolver1.buildAndAddToIndex(commonJavaLibraryRuleBuilder);
    ruleResolver2.buildAndAddToIndex(commonJavaLibraryRuleBuilder);

    // Create a java_library() rule with no deps.
    DefaultJavaLibraryRule.Builder javaLibraryBuilder = DefaultJavaLibraryRule
        .newJavaLibraryRuleBuilder(builderParams)
        .setBuildTarget(BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:cli"))
        // The source file must be an existing file or else RuleKey.Builder.setVal(File) will throw
        // an IOException, which is caught and then results in the rule being flagged as
        // "not idempotent", which screws up this test.
        // TODO(mbolin): Update RuleKey.Builder.setVal(File) to use a ProjectFilesystem so that file
        // access can be mocked appropriately during a unit test.
        .addSrc(Paths.get("src/com/facebook/buck/cli/Main.java"));
    DefaultJavaLibraryRule libraryNoCommon = ruleResolver1.buildAndAddToIndex(
        javaLibraryBuilder);

    // Create the same java_library() rule, but with a dep on //src/com/facebook/buck/cli:common.
    javaLibraryBuilder.addDep(BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:common"));
    DefaultJavaLibraryRule libraryWithCommon = ruleResolver2.buildAndAddToIndex(
        javaLibraryBuilder);

    // Assert that the RuleKeys are distinct.
    RuleKey r1 = libraryNoCommon.getRuleKey();
    RuleKey r2 = libraryWithCommon.getRuleKey();
    assertThat("Rule keys should be distinct because the deps of the rules are different.",
        r1,
        not(equalTo(r2)));
  }
}

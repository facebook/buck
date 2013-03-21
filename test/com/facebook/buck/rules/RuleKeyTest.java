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
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTargetFactory;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.util.Map;

/**
 * Unit test for {@link RuleKey}.
 */
public class RuleKeyTest {
  private static final ArtifactCache artifactCache = new NoopArtifactCache();

  /**
   * Ensure that build rules with the same inputs but different deps have unique RuleKeys.
   */
  @Test
  public void testRuleKeyDependsOnDeps() {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

    // Create a dependent build rule, //src/com/facebook/buck/cli:common.
    DefaultJavaLibraryRule commonRule = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:common"))
        .setArtifactCache(artifactCache)
        .build(buildRuleIndex);
    buildRuleIndex.put("//src/com/facebook/buck/cli:common", commonRule);

    // Create a java_library() rule with no deps.
    DefaultJavaLibraryRule.Builder javaLibraryBuilder = DefaultJavaLibraryRule
        .newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:cli"))
        // The source file must be an existing file or else RuleKey.Builder.setVal(File) will throw
        // an IOException, which is caught and then results in the rule being flagged as
        // "not idempotent", which screws up this test.
        // TODO(mbolin): Update RuleKey.Builder.setVal(File) to use a ProjectFilesystem so that file
        // access can be mocked appropriately during a unit test.
        .addSrc("src/com/facebook/buck/cli/Main.java")
        .setArtifactCache(artifactCache);
    DefaultJavaLibraryRule libraryNoCommon = javaLibraryBuilder.build(buildRuleIndex);

    // Create the same java_library() rule, but with a dep on //src/com/facebook/buck/cli:common.
    javaLibraryBuilder.addDep("//src/com/facebook/buck/cli:common");
    DefaultJavaLibraryRule libraryWithCommon = javaLibraryBuilder
        .setArtifactCache(artifactCache)
        .build(buildRuleIndex);

    // Assert that the RuleKeys are distinct.
    RuleKey r1 = libraryNoCommon.getRuleKey();
    RuleKey r2 = libraryWithCommon.getRuleKey();
    assertThat("Rule keys should be distinct because the deps of the rules are different.",
        r1,
        not(equalTo(r2)));
  }
}

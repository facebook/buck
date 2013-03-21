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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.After;
import org.junit.Test;

public class ShTestRuleTest extends EasyMockSupport {

  private static final ArtifactCache artifactCache = new NoopArtifactCache();

  @After
  public void tearDown() {
    // I don't understand why EasyMockSupport doesn't do this by default.
    verifyAll();
  }

  @Test
  public void testIsTestRequiredIfDebug() {
    ShTestRule shTest = createShTestRule(/* isRuleBuiltFromCache */ true);

    BuildContext buildContext = createMock(BuildContext.class);
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    EasyMock.expect(executionContext.isDebugEnabled()).andReturn(true);

    replayAll();

    assertTrue("Test run should be required when --debug is passed to `buck test`.",
        shTest.isTestRunRequired(buildContext, executionContext));
  }

  @Test
  public void testIsTestRequiredIfNotDebugAndNotCached() {
    ShTestRule shTest = createShTestRule(/* isRuleBuiltFromCache */ false);

    BuildContext buildContext = createMock(BuildContext.class);
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    EasyMock.expect(executionContext.isDebugEnabled()).andReturn(false);

    replayAll();

    assertTrue("Test run should be required if the rule is not cached.",
        shTest.isTestRunRequired(buildContext, executionContext));
  }

  @Test
  public void testIsTestRequiredIfNotDebugAndCachedButResultDotJsonDoesNotExist() {
    ShTestRule shTest = createShTestRule(/* isRuleBuiltFromCache */ true);

    ExecutionContext executionContext = createMock(ExecutionContext.class);
    EasyMock.expect(executionContext.isDebugEnabled()).andReturn(false);

    ProjectFilesystem filesystem = createMock(ProjectFilesystem.class);
    EasyMock.expect(filesystem.isFile(shTest.getPathToTestOutputResult())).andReturn(false);
    BuildContext buildContext = createMock(BuildContext.class);
    EasyMock.expect(buildContext.getProjectFilesystem()).andReturn(filesystem);

    replayAll();

    assertTrue("Test run should be required if the result.json file does not exist.",
        shTest.isTestRunRequired(buildContext, executionContext));
  }

  /**
   * An {@code ShTestRule} whose {@link ShTestRule#isRuleBuiltFromCache()} method always returns
   * {@code true}.
   */
  private static ShTestRule createShTestRule(final boolean isRuleBuiltFromCache) {
    return new ShTestRule(
        createCachingBuildRuleParams(),
        "run_test.sh",
        /* labels */ ImmutableSet.<String>of()) {

      @Override
      public boolean isRuleBuiltFromCache() {
        return isRuleBuiltFromCache;
      }
    };
  }

  private static CachingBuildRuleParams createCachingBuildRuleParams() {
    return new CachingBuildRuleParams(
        BuildTargetFactory.newInstance("//test/com/example:my_sh_test"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* visibilityPatterns */ ImmutableSet.<BuildTargetPattern>of(),
        artifactCache);
  }
}

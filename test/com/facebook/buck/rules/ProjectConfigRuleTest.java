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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListenableFuture;

import org.easymock.EasyMockSupport;
import org.junit.After;
import org.junit.Test;

/**
 * Unit test for {@link ProjectConfigRule}.
 */
public class ProjectConfigRuleTest extends EasyMockSupport {

  @After
  public void tearDown() {
    verifyAll();
  }

  @Test
  public void testBuildIsIdempotent() {
    BuildContext context = createMock(BuildContext.class);
    replayAll();

    ProjectConfigRule projectConfig = createProjectConfig();
    ListenableFuture<BuildRuleSuccess> result1 = projectConfig.build(context);
    ListenableFuture<BuildRuleSuccess> result2 = projectConfig.build(context);

    assertNotNull("build() should return a non-null result", result1);
    assertSame("build() must be idempotent", result1, result2);
  }

  private ProjectConfigRule createProjectConfig() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    JavaLibraryRule javaRule = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//javatests:lib")));

    BuildRuleParams buildRuleParams = new BuildRuleParams(
        BuildTargetFactory.newInstance("//javatests:project_config"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* visibility */ ImmutableSet.<BuildTargetPattern>of());
    return new ProjectConfigRule(
        buildRuleParams,
        /* srcRule */ javaRule,
        /* srcRoots */ null,
        /* testRule */ null,
        /* testRoots */ null,
        /* isIntelliJPlugin */ false);
  }
}

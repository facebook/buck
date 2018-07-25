/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.rules.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithProviders;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** Sample usage of {@link DefaultBuildRuleInfoProvider} along with testing */
public class DefaultBuildRuleInfoProviderTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private BuildTarget buildTarget;
  private ProjectFilesystem projectFilesystem;
  private DefaultBuildRuleInfoProvider defaultProvider;

  @Before
  public void setUp() {
    buildTarget = BuildTargetFactory.newInstance("//fake:target");
    projectFilesystem = new FakeProjectFilesystem();

    defaultProvider =
        ImmutableDefaultBuildRuleInfoProvider.of(
            FakeBuildRuleWithProviders.class, buildTarget, null, projectFilesystem);
  }

  @Test
  public void defaultProviderReturnsCorrectData() {
    BuildRule buildRule =
        new FakeBuildRuleWithProviders(
            BuildRuleInfoProviderCollection.builder().put(defaultProvider).build());

    assertEquals(buildTarget, buildRule.getBuildTarget());
    assertEquals(null, buildRule.getSourcePathToOutput());
    assertEquals(projectFilesystem, buildRule.getProjectFilesystem());
    assertEquals(buildTarget.toString(), buildRule.getFullyQualifiedName());
    assertEquals("fake_build_rule_with_providers", buildRule.getType());
    assertTrue(buildRule.isCacheable());
  }

  @Test
  public void mismatchProviderTypeThrows() {
    expectedException.expect(IllegalStateException.class);

    new FakeBuildRuleWithProviders(
        BuildRuleInfoProviderCollection.builder()
            .put(
                ImmutableDefaultBuildRuleInfoProvider.of(
                    AbstractBuildRuleWithProviders.class, buildTarget, null, projectFilesystem))
            .build());
  }
}

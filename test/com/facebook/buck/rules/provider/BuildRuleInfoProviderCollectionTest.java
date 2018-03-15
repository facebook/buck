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

package com.facebook.buck.rules.provider;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** Sample of BuildRules and Providers demonstrating the provider interface */
public class BuildRuleInfoProviderCollectionTest {

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
  public void missingProviderThrows() {
    expectedException.expect(MissingProviderException.class);

    BuildRule buildRule =
        new FakeBuildRuleWithProviders(
            BuildRuleInfoProviderCollection.builder().put(defaultProvider).build());
    buildRule.getProvider(FakeBuildRuleInfoProvider.KEY);
  }

  @Test
  public void missingDefaultProviderThrows() {
    expectedException.expect(MissingProviderException.class);

    new FakeBuildRuleWithProviders(BuildRuleInfoProviderCollection.builder().build());
  }

  @Test
  public void unprovidableBuildRuleThrows() {
    expectedException.expect(UnsupportedOperationException.class);

    BuildRule buildRule = new FakeBuildRule(buildTarget, ImmutableSortedSet.of());
    buildRule.getProvider(AnotherFakeBuildRuleInfoProvider.KEY);
  }

  @Test
  public void findsCorrectProviders() {
    FakeBuildRuleInfoProvider fakeProvider = new FakeBuildRuleInfoProvider(1);
    AnotherFakeBuildRuleInfoProvider anotherFakeProvider = new AnotherFakeBuildRuleInfoProvider();

    BuildRuleInfoProviderCollection.Builder providers =
        BuildRuleInfoProviderCollection.builder()
            .put(fakeProvider)
            .put(anotherFakeProvider)
            .put(defaultProvider);
    BuildRule buildRule = new FakeBuildRuleWithProviders(providers.build());

    FakeBuildRuleInfoProvider fakeProvider1 = buildRule.getProvider(FakeBuildRuleInfoProvider.KEY);
    assertEquals(fakeProvider, fakeProvider1);
    assertEquals(1, fakeProvider1.getValue());
    assertEquals(anotherFakeProvider, buildRule.getProvider(AnotherFakeBuildRuleInfoProvider.KEY));
    assertEquals(defaultProvider, buildRule.getProvider(DefaultBuildRuleInfoProvider.KEY));

    BuildRuleInfoProviderCollection providerCollection = buildRule.getProviderCollection();
    assertEquals(
        buildRule.getProvider(FakeBuildRuleInfoProvider.KEY),
        providerCollection.get(FakeBuildRuleInfoProvider.KEY));
    assertEquals(
        buildRule.getProvider(AnotherFakeBuildRuleInfoProvider.KEY),
        providerCollection.get(AnotherFakeBuildRuleInfoProvider.KEY));
    assertEquals(defaultProvider, providerCollection.get(DefaultBuildRuleInfoProvider.KEY));
  }
}

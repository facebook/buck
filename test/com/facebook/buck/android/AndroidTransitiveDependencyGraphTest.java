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

package com.facebook.buck.android;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.google.common.collect.ImmutableSortedSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidTransitiveDependencyGraphTest {

  @Test
  public void findManifestFilesWithTransitiveDeps() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule dep3 =
        AndroidLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep3"))
            .setManifestFile(FakeSourcePath.of("manifest3.xml"))
            .build(graphBuilder);
    BuildRule dep2 =
        AndroidLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep2"))
            .addDep(dep3.getBuildTarget())
            .build(graphBuilder);
    BuildRule dep1 =
        AndroidLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep1"))
            .setManifestFile(FakeSourcePath.of("manifest1.xml"))
            .addDep(dep2.getBuildTarget())
            .build(graphBuilder);
    assertThat(
        new AndroidTransitiveDependencyGraph(ImmutableSortedSet.of(dep1)).findManifestFiles(),
        Matchers.containsInAnyOrder(
            FakeSourcePath.of("manifest1.xml"), FakeSourcePath.of("manifest3.xml")));
  }
}

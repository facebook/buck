/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;

import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class OmnibusRootsTest {

  @Test
  public void excludedAndIncludedDeps() throws NoSuchBuildTargetException {
    OmnibusRootNode transitiveRoot = new OmnibusRootNode("//:transitive_root");
    NativeLinkable excludedDep =
        new OmnibusSharedOnlyNode(
            "//:excluded_dep", ImmutableList.<NativeLinkable>of(transitiveRoot));
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(excludedDep));

    OmnibusRoots.Builder builder =
        OmnibusRoots.builder(CxxPlatformUtils.DEFAULT_PLATFORM, ImmutableSet.of());
    builder.addIncludedRoot(root);
    builder.addIncludedRoot(transitiveRoot);
    OmnibusRoots roots = builder.build();

    assertThat(roots.getIncludedRoots().keySet(), Matchers.contains(root.getBuildTarget()));
    assertThat(
        roots.getExcludedRoots().keySet(), Matchers.contains(transitiveRoot.getBuildTarget()));
  }
}

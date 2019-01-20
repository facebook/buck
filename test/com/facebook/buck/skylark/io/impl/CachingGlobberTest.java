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

package com.facebook.buck.skylark.io.impl;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.skylark.io.Globber;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;

public class CachingGlobberTest {

  private FakeGlobber fakeGlobber;
  private CachingGlobber cachingGlobber;

  @Before
  public void setUp() {
    fakeGlobber = new FakeGlobber();
    cachingGlobber = CachingGlobber.of(fakeGlobber);
  }

  @Test
  public void globberReturnsCachedValue() throws Exception {
    ImmutableSet<String> expectedExpandedPaths = ImmutableSet.of("path");
    fakeGlobber.returnResultOnNextInvocations(expectedExpandedPaths);
    Set<String> expandedPaths =
        cachingGlobber.run(ImmutableList.of("path"), ImmutableList.of(), false);
    assertThat(expandedPaths, is(expectedExpandedPaths));
    fakeGlobber.returnResultOnNextInvocations(ImmutableSet.of());
    expandedPaths = cachingGlobber.run(ImmutableList.of("path"), ImmutableList.of(), false);
    assertThat(expandedPaths, is(expectedExpandedPaths));
  }

  @Test
  public void differentKeysCanHaveDifferentValues() throws Exception {
    ImmutableSet<String> expectedExpandedPaths = ImmutableSet.of("path");
    fakeGlobber.returnResultOnNextInvocations(expectedExpandedPaths);
    Set<String> expandedPaths =
        cachingGlobber.run(ImmutableList.of("path"), ImmutableList.of(), false);
    assertThat(expandedPaths, is(expectedExpandedPaths));
    fakeGlobber.returnResultOnNextInvocations(ImmutableSet.of());
    expandedPaths =
        cachingGlobber.run(ImmutableList.of("does_not_exist"), ImmutableList.of(), false);
    assertThat(expandedPaths, is(ImmutableSet.of()));
  }

  @Test
  public void globManifestIncludesAllInvocations() throws Exception {
    ImmutableSet<String> expectedExpandedPaths = ImmutableSet.of("path");
    fakeGlobber.returnResultOnNextInvocations(expectedExpandedPaths);
    Set<String> expandedPaths =
        cachingGlobber.run(ImmutableList.of("path"), ImmutableList.of(), false);
    assertThat(expandedPaths, is(expectedExpandedPaths));
    fakeGlobber.returnResultOnNextInvocations(ImmutableSet.of());
    expandedPaths =
        cachingGlobber.run(ImmutableList.of("does_not_exist"), ImmutableList.of(), true);
    assertThat(expandedPaths, is(ImmutableSet.of()));

    assertThat(
        cachingGlobber.createGlobManifest(),
        equalTo(
            ImmutableList.of(
                GlobSpecWithResult.of(
                    GlobSpec.builder()
                        .setInclude(ImmutableList.of("does_not_exist"))
                        .setExclude(ImmutableList.of())
                        .setExcludeDirectories(true)
                        .build(),
                    ImmutableSet.of()),
                GlobSpecWithResult.of(
                    GlobSpec.builder()
                        .setInclude(ImmutableList.of("path"))
                        .setExclude(ImmutableList.of())
                        .setExcludeDirectories(false)
                        .build(),
                    ImmutableSet.of("path")))));
  }

  private static class FakeGlobber implements Globber {
    @Nullable private Set<String> result;

    @Override
    public Set<String> run(
        Collection<String> include, Collection<String> exclude, boolean excludeDirectories) {
      return result;
    }

    public void returnResultOnNextInvocations(Set<String> result) {
      this.result = result;
    }
  }
}

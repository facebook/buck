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

package com.facebook.buck.java;

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

public class JavaBinaryDescriptionTest {

  @Test
  public void rulesExportedFromDepsBecomeFirstOrderDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    FakeJavaLibrary transitiveLibrary =
        resolver.addToIndex(
            new FakeJavaLibrary(
                BuildTargetFactory.newInstance("//:transitive_lib"),
                pathResolver));
    FakeJavaLibrary firstOrderLibrary =
        resolver.addToIndex(
            new FakeJavaLibrary(
                BuildTargetFactory.newInstance("//:first_order_lib"),
                pathResolver,
                ImmutableSortedSet.<BuildRule>of(transitiveLibrary)));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule javaBinary = new JavaBinaryRuleBuilder(target)
        .setDeps(ImmutableSortedSet.of(firstOrderLibrary.getBuildTarget()))
        .build(resolver);

    assertThat(
        javaBinary.getDeps(),
        Matchers.<BuildRule>containsInAnyOrder(firstOrderLibrary, transitiveLibrary));
  }

}

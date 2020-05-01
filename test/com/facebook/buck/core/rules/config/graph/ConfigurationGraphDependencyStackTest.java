/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.rules.config.graph;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ConfigurationGraphDependencyStackTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void cycle() {
    ConfigurationGraphDependencyStack stack =
        ConfigurationGraphDependencyStack.root(DependencyStack.root());

    stack = stack.child(UnconfiguredBuildTargetFactoryForTests.newInstance("//foo:bar"));
    stack = stack.child(UnconfiguredBuildTargetFactoryForTests.newInstance("//foo:baz"));
    stack = stack.child(UnconfiguredBuildTargetFactoryForTests.newInstance("//foo:qux"));

    Assert.assertTrue(
        stack.contains(
            UnconfiguredBuildTargetFactoryForTests.newInstance("//foo:bar")
                .getUnflavoredBuildTarget()));
    Assert.assertTrue(
        stack.contains(
            UnconfiguredBuildTargetFactoryForTests.newInstance("//foo:baz")
                .getUnflavoredBuildTarget()));
    Assert.assertTrue(
        stack.contains(
            UnconfiguredBuildTargetFactoryForTests.newInstance("//foo:qux")
                .getUnflavoredBuildTarget()));
    Assert.assertFalse(
        stack.contains(
            UnconfiguredBuildTargetFactoryForTests.newInstance("//foo:quux")
                .getUnflavoredBuildTarget()));

    thrown.expect(HumanReadableException.class);

    stack.child(UnconfiguredBuildTargetFactoryForTests.newInstance("//foo:baz"));
  }
}

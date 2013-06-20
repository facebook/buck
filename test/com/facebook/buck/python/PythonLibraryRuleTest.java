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

package com.facebook.buck.python;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

/**
 * Unit test for {@link PythonLibraryRule}.
 */
public class PythonLibraryRuleTest {

  @Test
  public void testGetters() {
    BuildRuleParams buildRuleParams = new BuildRuleParams(
        BuildTargetFactory.newInstance("//scripts/python:foo"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* visibilityPatterns */ ImmutableSet.<BuildTargetPattern>of(),
        /* pathRelativizer */ Functions.<String>identity());
    ImmutableSortedSet<String> srcs = ImmutableSortedSet.of("");
    PythonLibraryRule pythonLibraryRule = new PythonLibraryRule(
        buildRuleParams,
        srcs);

    assertTrue(pythonLibraryRule.isLibrary());
    assertSame(srcs, pythonLibraryRule.getPythonSrcs());
  }
}

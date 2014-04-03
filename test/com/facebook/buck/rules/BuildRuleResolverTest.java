/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;

import org.junit.Test;

public class BuildRuleResolverTest {

  @Test
  public void testBuildAndAddToIndexRejectsDuplicateBuildTarget() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();

    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    JavaLibraryBuilder.createBuilder(target).build(buildRuleResolver);

    // A BuildRuleResolver should allow only one entry for a BuildTarget.
    try {
      JavaLibraryBuilder.createBuilder(target).build(buildRuleResolver);
      fail("Should throw IllegalStateException.");
    } catch (IllegalStateException e) {
      assertEquals(
          "A build rule for this target has already been created: " + target,
          e.getMessage());
    }
  }
}

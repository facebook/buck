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

package com.facebook.buck.cpp;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.DirectoryTraverser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;


public class PrebuiltNativeLibraryRuleTest {

  @Test
  public void testGetInputsToCompareToOutput() {
    // Mock out the traversal of the res/ and assets/ directories. Note that the directory entries
    // are not traversed in alphabetical order because ensuring that a sort happens is part of what
    // we are testing.
    DirectoryTraverser traverser = new DirectoryTraverser() {
      @Override
      public void traverse(DirectoryTraversal traversal) {
        String rootPath = traversal.getRoot().getPath();
        if ("java/src/com/facebook/base/libs".equals(rootPath)) {
          traversal.visit(null, "armeabi/foo.so");
          traversal.visit(null, "armeabi/libilbc-codec.so");
          traversal.visit(null, "armeabi/bar.so");
        } else {
          throw new RuntimeException("Unexpected path: " + rootPath);
        }
      }
    };

    // Create an android_library rule with all sorts of input files that it depends on. If any of
    // these files is modified, then this rule should not be cached.
    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base", "base");
    BuildRuleParams buildRuleParams = new BuildRuleParams(
        buildTarget,
        ImmutableSortedSet.<BuildRule>of() /* deps */,
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
    PrebuiltNativeLibraryBuildRule nativeLibraryRule = new PrebuiltNativeLibraryBuildRule(
        buildRuleParams,
        "java/src/com/facebook/base/libs",
        traverser);

    // Test getInputsToCompareToOutput().
    MoreAsserts.assertIterablesEquals(
        "Each subgroup of input files should be sorted alphabetically so that the list order is " +
        "consistent even if the iteration order of the sets passed to the AndroidLibraryRule " +
        "changes.",
        ImmutableList.of(
          "java/src/com/facebook/base/libs/armeabi/bar.so",
          "java/src/com/facebook/base/libs/armeabi/foo.so",
          "java/src/com/facebook/base/libs/armeabi/libilbc-codec.so"),
          nativeLibraryRule.getInputsToCompareToOutput());
  }
}

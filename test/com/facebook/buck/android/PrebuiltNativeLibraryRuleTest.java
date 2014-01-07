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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.MorePaths;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;


public class PrebuiltNativeLibraryRuleTest {

  @Test
  public void testGetInputsToCompareToOutput() {
    // Mock out the traversal of the res/ and assets/ directories. Note that the directory entries
    // are not traversed in alphabetical order because ensuring that a sort happens is part of what
    // we are testing.
    DirectoryTraverser traverser = new DirectoryTraverser() {
      @Override
      public void traverse(DirectoryTraversal traversal) throws IOException {
        String rootPath = MorePaths.newPathInstance(traversal.getRoot()).toString();
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
    PrebuiltNativeLibrary nativeLibraryRule = new PrebuiltNativeLibrary(
        Paths.get("java/src/com/facebook/base/libs"),
        false,
        traverser);

    assertEquals(nativeLibraryRule.getLibraryPath(), Paths.get("java/src/com/facebook/base/libs"));
    assertFalse(nativeLibraryRule.isAsset());

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

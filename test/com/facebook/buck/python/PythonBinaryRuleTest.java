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

package com.facebook.buck.python;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Paths;

public class PythonBinaryRuleTest {
  @Test
  public void testGetPythonPathEntries() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget orphanPyLibraryTarget = BuildTargetFactory.newInstance("//:orphan_python_library");
    ruleResolver.buildAndAddToIndex(
        PythonLibrary.newPythonLibraryBuilder(new FakeAbstractBuildRuleBuilderParams())
            .addSrc("java/src/com/javalib/orphan/sadpanda.py")
            .setBuildTarget(orphanPyLibraryTarget)
            .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:javalib");
    ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(javaLibraryTarget)
            .addSrc("java/src/com/javalib/Bar.java")
            .addDep(orphanPyLibraryTarget)
            .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    BuildTarget pyLibraryTarget = BuildTargetFactory.newInstance("//:py_library");
    ruleResolver.buildAndAddToIndex(
        PythonLibrary.newPythonLibraryBuilder(new FakeAbstractBuildRuleBuilderParams())
            .addSrc("python/tastypy.py")
            .setBuildTarget(pyLibraryTarget)
            .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    BuildTarget pyBinaryTarget = BuildTargetFactory.newInstance("//:py_binary");
    PythonBinaryRule pyBinary = ruleResolver.buildAndAddToIndex(
        PythonBinaryRule.newPythonBinaryBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setMain("foo")
            .addDep(javaLibraryTarget)
            .addDep(pyLibraryTarget)
            .setBuildTarget(pyBinaryTarget)
            .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    assertEquals(ImmutableSet.of(Paths.get("buck-out/gen/__pylib_py_library")),
        pyBinary.getPythonPathEntries());
  }
}

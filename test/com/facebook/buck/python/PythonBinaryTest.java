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

import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DescribedRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class PythonBinaryTest {

  @Test
  public void testPythonBinaryGetSourcesMethodReturnsTransitiveSourceMap() {
    BuildTarget orphanPyLibraryTarget = new BuildTarget("//", "orphan_python_library");
    PythonLibrary orphanPyLibrary = new PythonLibrary(
        orphanPyLibraryTarget,
        ImmutableSortedSet.<SourcePath>of(
            new TestSourcePath("java/src/com/javalib/orphan/sadpanda.py")),
        ImmutableSortedSet.<SourcePath>of());
    BuildRule orphanPyLibraryRule = createBuildRule(orphanPyLibrary, orphanPyLibraryTarget);

    BuildTarget pyLibraryTarget = BuildTargetFactory.newInstance("//:py_library");
    PythonLibrary pyLibrary = new PythonLibrary(
        pyLibraryTarget,
        ImmutableSortedSet.<SourcePath>of(
            new TestSourcePath("python/tastypy.py")),
        ImmutableSortedSet.<SourcePath>of());

    Map<BuildTarget, BuildRule> rules = Maps.newHashMap();
    rules.put(orphanPyLibraryTarget, createBuildRule(orphanPyLibrary, orphanPyLibraryTarget));
    BuildRule pyLibraryRule = createBuildRule(pyLibrary, pyLibraryTarget);
    rules.put(pyLibraryTarget, pyLibraryRule);
    BuildRuleResolver ruleResolver = new BuildRuleResolver(rules);

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:javalib");
    BuildRule javaLibrary = JavaLibraryBuilder
        .createBuilder(javaLibraryTarget)
        .addSrc(Paths.get("java/src/com/javalib/Bar.java"))
        .addDep(orphanPyLibraryRule)
        .build(ruleResolver);

    Path foo = Paths.get("foo");
    PythonBinary buildable = new PythonBinary(
        new BuildTarget("//", "python_binary"),
        ImmutableSortedSet.<BuildRule>of(javaLibrary, pyLibraryRule),
        foo);

    assertEquals(
        new PythonPackageComponents.Builder("test")
            .addModule(foo, foo, "")
            .addComponent(pyLibrary.getPythonPackageComponents(), "")
            .build(),
        buildable.getAllComponents());
  }

  // Verify that we detect output path conflicts between different rules.
  @Test
  public void testPathConflictThrowsHumanReadableError() {

    // The path that conflicts.
    Path tasty = Paths.get("python/tastypy.py");

    // A python library which specifies the above path.
    BuildTarget pyLibraryTarget = BuildTargetFactory.newInstance("//:py_library");
    PythonLibrary pyLibrary = new PythonLibrary(
        pyLibraryTarget,
        ImmutableSortedSet.<SourcePath>of(
            new TestSourcePath(tasty.toString())),
        ImmutableSortedSet.<SourcePath>of());
    BuildRule pyLibraryRule = createBuildRule(pyLibrary, pyLibraryTarget);

    // The top-level python binary that lists the above library as a dep and
    // also lists the "tasty" path as its main module, which will conflict.
    PythonBinary buildable = new PythonBinary(
        new BuildTarget("//", "python_binary"),
        ImmutableSortedSet.<BuildRule>of(pyLibraryRule),
        tasty);

    // Try to grab the overall package componets for the binary, which should
    // fail due to the conflict.
    try {
      buildable.getAllComponents();
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          containsString("found duplicate entries for module " + tasty.toString()));
    }

  }

  private static BuildRule createBuildRule(PythonLibrary pythonLibrary, BuildTarget buildTarget) {
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget).build();
    return new DescribedRule(PythonLibraryDescription.TYPE,
        pythonLibrary,
        params);
  }

}

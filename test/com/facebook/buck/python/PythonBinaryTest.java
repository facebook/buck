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

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.Map;

public class PythonBinaryTest {
  @Test
  public void testGetPythonPathEntries() {
    BuildTarget orphanPyLibraryTarget = new BuildTarget("//", "orphan_python_library");
    PythonLibrary orphanPyLibrary = new PythonLibrary(
        new FakeBuildRuleParams(orphanPyLibraryTarget),
        ImmutableSortedSet.<SourcePath>of(
            new TestSourcePath("java/src/com/javalib/orphan/sadpanda.py")));
    BuildRule orphanPyLibraryRule = createBuildRule(orphanPyLibrary, orphanPyLibraryTarget);

    BuildTarget pyLibraryTarget = BuildTargetFactory.newInstance("//:py_library");
    PythonLibrary pyLibrary = new PythonLibrary(
        new FakeBuildRuleParams(pyLibraryTarget),
        ImmutableSortedSet.<SourcePath>of(
            new TestSourcePath("python/tastypy.py")));

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

    PythonBinary buildable = new PythonBinary(
        ImmutableSortedSet.<BuildRule>of(javaLibrary, pyLibraryRule),
        Paths.get("foo"));

    assertEquals(ImmutableSet.of(Paths.get("buck-out/gen/__pylib_py_library")),
        buildable.getPythonPathEntries());
  }

  private static BuildRule createBuildRule(PythonLibrary pythonLibrary, BuildTarget buildTarget) {
    BuildRuleParams params = new FakeBuildRuleParams(buildTarget);
    return new AbstractBuildable.AnonymousBuildRule(PythonLibraryDescription.TYPE,
        pythonLibrary,
        params);
  }
}

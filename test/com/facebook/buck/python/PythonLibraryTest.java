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

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;
import static org.easymock.EasyMock.createMock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Unit test for {@link PythonLibrary}.
 */
public class PythonLibraryTest {
  @Rule
  public final TemporaryFolder projectRootDir = new TemporaryFolder();

  @Test
  public void testGetters() {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(
        new BuildTarget("//scripts/python", "foo"));
    SourcePath src = new FileSourcePath("");
    ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of(src);
    PythonLibrary pythonLibrary = new PythonLibrary(
        buildRuleParams,
        srcs);

    assertTrue(pythonLibrary.getProperties().is(LIBRARY));
  }

  @Test
  public void testFlattening() throws IOException {
    BuildTarget pyLibraryTarget = BuildTargetFactory.newInstance("//:py_library");
    ImmutableSortedSet.Builder<SourcePath> srcs = ImmutableSortedSet.naturalOrder();
    srcs.add(new FileSourcePath("baz.py"));
    srcs.add(new FileSourcePath("foo/__init__.py"));
    srcs.add(new FileSourcePath("foo/bar.py"));
    PythonLibrary rule = new PythonLibrary(new FakeBuildRuleParams(pyLibraryTarget),
        srcs.build());

    FakeBuildableContext buildableContext = new FakeBuildableContext();
    BuildContext buildContext = createMock(BuildContext.class);
    List<Step> steps = rule.getBuildSteps(buildContext, buildableContext);

    final String projectRoot = projectRootDir.getRoot().getAbsolutePath();
    final String pylibpath = "__pylib_py_library";

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(new File(projectRoot));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
      .setProjectFilesystem(projectFilesystem)
      .build();

    MoreAsserts.assertSteps(
        "python_library() should ensure each file is linked and has its destination directory made",
        ImmutableList.of(
            String.format(
              "mkdir -p %s/%s/%s",
              projectRoot,
              BuckConstant.GEN_DIR,
              pylibpath
              ),
            String.format(
              "mkdir -p %s/%s/%s/foo",
              projectRoot,
              BuckConstant.GEN_DIR,
              pylibpath
              ),
            String.format(
              "ln -f -s ../../../baz.py %s/%s/%s/baz.py",
              projectRoot,
              BuckConstant.GEN_DIR,
              pylibpath
              ),
            String.format(
              "ln -f -s ../../../../foo/__init__.py %s/%s/%s/foo/__init__.py",
              projectRoot,
              BuckConstant.GEN_DIR,
              pylibpath
              ),
            String.format(
              "ln -f -s ../../../../foo/bar.py %s/%s/%s/foo/bar.py",
              projectRoot,
              BuckConstant.GEN_DIR,
              pylibpath
              )
        ),
        steps.subList(1, 6),
        executionContext);

    ImmutableSet<Path> artifacts = buildableContext.getRecordedArtifacts();
    assertEquals(
      ImmutableSet.of(
        Paths.get("buck-out/gen/__pylib_py_library/baz.py"),
        Paths.get("buck-out/gen/__pylib_py_library/foo/__init__.py"),
        Paths.get("buck-out/gen/__pylib_py_library/foo/bar.py")
      ),
      artifacts);
  }
}

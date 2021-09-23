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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PrebuiltJarTest {

  @Rule public TemporaryPaths temp = new TemporaryPaths();

  private PrebuiltJar junitJarRule;
  private FakeProjectFilesystem filesystem;
  private JavaLibrary declaredDep;
  private JavaLibrary extraDep;

  @Before
  public void setUp() throws IOException {
    filesystem = new FakeProjectFilesystem(temp.newFolder());

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//lib:junit");
    declaredDep = new FakeJavaLibrary(BuildTargetFactory.newInstance("//lib:declared_dep"));
    extraDep = new FakeJavaLibrary(BuildTargetFactory.newInstance("//lib:extra_dep"));
    BuildRuleParams buildRuleParams =
        TestBuildRuleParams.create()
            .withDeclaredDeps(ImmutableSortedSet.of(declaredDep))
            .withExtraDeps(ImmutableSortedSet.of(extraDep));

    junitJarRule =
        new PrebuiltJar(
            buildTarget,
            filesystem,
            buildRuleParams,
            new TestActionGraphBuilder().getSourcePathResolver(),
            FakeSourcePath.of("abi.jar"),
            /* mavenCoords */ Optional.empty(),
            /* requiredForSourceOnlyAbi */ false,
            /* generateAbi */ true,
            /* neverMarkAsUnusedDependency */ false,
            /* shouldDesugarInterfaceMethodsInPrebuiltJars */ false);
  }

  @Test
  public void testGetJavaSrcsIsEmpty() {
    assertTrue(junitJarRule.getJavaSrcs().isEmpty());
  }

  @Test
  public void testGetAnnotationProcessingDataIsEmpty() {
    assertFalse(junitJarRule.getGeneratedAnnotationSourcePath().isPresent());
    assertFalse(junitJarRule.hasAnnotationProcessing());
  }

  @Test
  public void testGetDepsForTransitiveClasspathEntries() {
    assertEquals(1, junitJarRule.getDepsForTransitiveClasspathEntries().size());
    assertTrue(junitJarRule.getDepsForTransitiveClasspathEntries().contains(declaredDep));
  }
}

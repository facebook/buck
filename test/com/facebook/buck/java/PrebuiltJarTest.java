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

package com.facebook.buck.java;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PrebuiltJarTest {

  private static final Path PATH_TO_JUNIT_JAR = Paths.get("third-party/java/junit/junit-4.11.jar");

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private PrebuiltJar junitJarRule;
  private FakeProjectFilesystem filesystem;


  @Before
  public void setUp() throws IOException {
    filesystem = new FakeProjectFilesystem(temp.newFolder());

    BuildRuleParams buildRuleParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//lib", "junit").build())
            .setProjectFilesystem(filesystem)
            .build();

    junitJarRule = new PrebuiltJar(
        buildRuleParams,
        new SourcePathResolver(new BuildRuleResolver()),
        new PathSourcePath(filesystem, PATH_TO_JUNIT_JAR),
        Optional.<SourcePath>of(new TestSourcePath("lib/junit-4.11-sources.jar")),
        /* gwtJar */ Optional.<SourcePath>absent(),
        Optional.of("http://junit-team.github.io/junit/javadoc/latest/"));
  }

  @Test
  public void testGetJavaSrcsIsEmpty() {
    assertTrue(junitJarRule.getJavaSrcs().isEmpty());
  }

  @Test
  public void testGetAnnotationProcessingDataIsEmpty() {
    assertTrue(junitJarRule.getAnnotationProcessingParams().isEmpty());
  }
}

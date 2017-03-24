/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.RichStream;

import org.junit.Before;
import org.junit.Test;

public class JsLibraryDescriptionTest {

  private static final String targetDirectory = "arbitrary/path";
  private JsTestScenario.Builder scenarioBuilder;
  private BuildTarget target;

  @Before
  public void setUp() throws NoSuchBuildTargetException {
    scenarioBuilder = JsTestScenario.builder();
    target = BuildTargetFactory.newInstance(String.format("//%s:target", targetDirectory));
  }

  @Test
  public void subBasePathForSourceFiles() throws NoSuchBuildTargetException {
    final String basePath = "base/path";
    final String filePath = String.format("%s/sub/file.js", targetDirectory);
    final JsTestScenario scenario = buildScenario(basePath, new FakeSourcePath(filePath));

    assertEquals(
        "arbitrary/path/base/path/sub/file.js",
        findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void relativeBasePathForSourceFiles() throws NoSuchBuildTargetException {
    final String basePath = "../base/path";
    final String filePath = String.format("%s/sub/file.js", targetDirectory);
    final JsTestScenario scenario = buildScenario(basePath, new FakeSourcePath(filePath));

    assertEquals(
        "arbitrary/base/path/sub/file.js",
        findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void basePathReplacesBuildTargetSourcePath() throws NoSuchBuildTargetException {
    final String basePath = "base/path.js";
    final BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    scenarioBuilder.arbitraryRule(target);
    final JsTestScenario scenario = buildScenario(
        basePath,
        new DefaultBuildTargetSourcePath(target));

    assertEquals(
        "arbitrary/path/base/path.js",
        findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void relativeBasePathReplacesBuildTargetSourcePath() throws NoSuchBuildTargetException {
    final String basePath = "../path.js";
    final BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    scenarioBuilder.arbitraryRule(target);
    final JsTestScenario scenario = buildScenario(
        basePath,
        new DefaultBuildTargetSourcePath(target));

    assertEquals(
        "arbitrary/path.js",
        findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void buildTargetWithSubpathPair() throws NoSuchBuildTargetException {
    final String basePath = ".";
    final BuildTarget target = BuildTargetFactory.newInstance("//:node_modules");
    scenarioBuilder.arbitraryRule(target);
    final JsTestScenario scenario = buildScenario(
        basePath,
        new Pair<>(new DefaultBuildTargetSourcePath(target), "node_modules/left-pad/index.js"));

    assertEquals(
        "arbitrary/path/node_modules/left-pad/index.js",
        findFileRule(scenario.resolver).getVirtualPath().get());
  }

  private JsTestScenario buildScenario(
      String basePath,
      SourcePath source) throws NoSuchBuildTargetException {
    return scenarioBuilder
        .library(target, basePath, source)
        .build();
  }

  private JsTestScenario buildScenario(
      String basePath,
      Pair<SourcePath, String> source) throws NoSuchBuildTargetException {
    return scenarioBuilder
        .library(target, basePath, source)
        .build();
  }

  private JsFile.JsFileDev findFileRule(BuildRuleResolver resolver) {
    return RichStream.from(resolver
        .getRule(target)
        .getBuildDeps())
        .filter(JsFile.JsFileDev.class)
        .findFirst()
        .get();
  }
}

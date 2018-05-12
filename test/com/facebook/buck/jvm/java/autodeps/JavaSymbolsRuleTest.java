/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.autodeps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.Rule;
import org.junit.Test;

public class JavaSymbolsRuleTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void ensureJsonFilesGetWritten() throws IOException, InterruptedException {
    TestDataHelper.createProjectWorkspaceForScenario(this, "java_library_symbols_finder", tmp)
        .setUp();
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    ImmutableSortedSet<SourcePath> srcs =
        ImmutableSortedSet.<SourcePath>naturalOrder()
            .addAll(
                Stream.of("Example1.java", "Example2.java")
                    .map(Paths::get)
                    .map(p -> PathSourcePath.of(projectFilesystem, p))
                    .iterator())
            .add(DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//foo:bar")))
            .build();
    JavaFileParser javaFileParser =
        JavaFileParser.createJavaFileParser(
            JavacOptions.builder().setSourceLevel("7").setTargetLevel("7").build());
    JavaLibrarySymbolsFinder symbolsFinder =
        new JavaLibrarySymbolsFinder(srcs, javaFileParser /* shouldRecordRequiredSymbols */);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:examples");
    JavaSymbolsRule javaSymbolsRule =
        new JavaSymbolsRule(buildTarget, symbolsFinder, projectFilesystem);
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    resolver.addToIndex(javaSymbolsRule);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    List<Step> buildSteps =
        javaSymbolsRule.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), /* buildableContext */ null);

    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setCellPathResolver(TestCellPathResolver.get(projectFilesystem))
            .build();

    for (Step step : buildSteps) {
      step.execute(executionContext);
    }

    try (JsonParser parser =
        ObjectMappers.createParser(
            projectFilesystem.resolve(
                BuildTargets.getGenPath(
                    javaSymbolsRule.getProjectFilesystem(),
                    buildTarget.withFlavors(JavaSymbolsRule.JAVA_SYMBOLS),
                    "__%s__.json")))) {
      JsonNode jsonNode = ObjectMappers.READER.readTree(parser);
      assertTrue(jsonNode instanceof ObjectNode);
      assertEquals(
          ImmutableSet.of("com.example.Example1", "com.example.Example2"),
          StreamSupport.stream(jsonNode.get("provided").spliterator(), false)
              .map(JsonNode::textValue)
              .collect(ImmutableSet.toImmutableSet()));
    }
  }
}

/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.BuildRuleSuccess.Type;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.Ansi;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.annotation.Nullable;

public class BuildCommandTest {

  @Test
  public void testGenerateBuildReport() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);
    BuildRule rule1 = new FakeBuildRule(
        BuildTargetFactory.newInstance("//fake:rule1"),
        resolver) {
      @Override
      @Nullable
      public Path getPathToOutputFile() {
        return Paths.get("buck-out/gen/fake/rule1.txt");
      }
    };
    BuildRule rule2 = new FakeBuildRule(
        BuildTargetFactory.newInstance("//fake:rule2"),
        resolver);
    BuildRule rule3 = new FakeBuildRule(
        BuildTargetFactory.newInstance("//fake:rule3"),
        resolver);

    List<BuildRule> rulesToBuild = ImmutableList.of(rule1, rule2, rule3);
    List<BuildRuleSuccess> results = Lists.newArrayList();
    results.add(new BuildRuleSuccess(rule1, Type.BUILT_LOCALLY));
    results.add(null);
    results.add(new BuildRuleSuccess(rule3, Type.FETCHED_FROM_CACHE));

    String expectedReport =
        "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule1 " +
            "BUILT_LOCALLY buck-out/gen/fake/rule1.txt\n" +
        "\u001B[1m\u001B[41m\u001B[37mFAIL\u001B[0m //fake:rule2\n" +
        "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule3 FETCHED_FROM_CACHE\n";
    String observedReport = BuildCommand.generateBuildReport(
        rulesToBuild,
        results,
        Ansi.forceTty());
    assertEquals(expectedReport, observedReport);
  }
}

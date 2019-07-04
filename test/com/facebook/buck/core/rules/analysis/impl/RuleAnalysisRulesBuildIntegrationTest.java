/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.analysis.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class RuleAnalysisRulesBuildIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void ruleAnalysisRuleBuilds() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_rule", tmp);

    workspace.setUp();

    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownNativeRuleTypes.of(
                    ImmutableList.of(new FakeRuleDescription()), ImmutableList.of()));

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    assertEquals(ImmutableList.of("testcontent"), Files.readAllLines(resultPath));
  }

  @Test
  public void ruleAnalysisRuleWithDepsBuildsAndRebuildsOnChange() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_deps", tmp);

    workspace.setUp();

    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownNativeRuleTypes.of(
                    ImmutableList.of(new BasicRuleDescription()), ImmutableList.of()));

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * bar1dep{
     *    baz4dep{
     *    }
     *    foo2dep{
     *      baz4dep{
     *      }
     *    }
     *    faz0dep{
     *    }
     * }
     * </pre>
     */
    List<String> lines = Files.readAllLines(resultPath);

    assertEquals("bar1dep{", lines.get(0));

    assertNotEquals(-1, lines.indexOf("baz4dep{"));
    assertNotEquals(-1, lines.indexOf("faz0dep{"));
    int indexFoo = lines.indexOf("foo2dep{");
    assertNotEquals(-1, indexFoo);

    assertEquals("baz4dep{", lines.get(indexFoo + 1));

    workspace.writeContentsToPath(
        "basic_rule(\n"
            + "    name = \"faz\",\n"
            + "    val = 10,\n"
            + "    visibility = [\"PUBLIC\"],\n"
            + ")",
        "dir/BUCK");

    resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * bar1dep{
     *    baz4dep{
     *    }
     *    foo2dep{
     *      baz4dep{
     *      }
     *    }
     *    faz10dep{
     *    }
     * }
     * </pre>
     */
    lines = Files.readAllLines(resultPath);

    assertEquals("bar1dep{", lines.get(0));

    assertNotEquals(-1, lines.indexOf("baz4dep{"));
    assertNotEquals(-1, lines.indexOf("faz10dep{"));
    indexFoo = lines.indexOf("foo2dep{");
    assertNotEquals(-1, indexFoo);

    assertEquals("baz4dep{", lines.get(indexFoo + 1));
  }
}

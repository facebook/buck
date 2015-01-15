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

import com.facebook.buck.command.BuildReport;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.BuildRuleSuccess.Type;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.Ansi;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;

import javax.annotation.Nullable;

public class BuildCommandTest {

  private LinkedHashMap<BuildRule, Optional<BuildRuleSuccess>> ruleToResult;

  @Before
  public void setUp() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    ruleToResult = new LinkedHashMap<>();

    BuildRule rule1 = new FakeBuildRule(
        BuildTargetFactory.newInstance("//fake:rule1"),
        resolver) {
      @Override
      @Nullable
      public Path getPathToOutputFile() {
        return Paths.get("buck-out/gen/fake/rule1.txt");
      }
    };
    ruleToResult.put(rule1, Optional.of(new BuildRuleSuccess(rule1, Type.BUILT_LOCALLY)));

    BuildRule rule2 = new FakeBuildRule(
        BuildTargetFactory.newInstance("//fake:rule2"),
        resolver);
    ruleToResult.put(rule2, Optional.<BuildRuleSuccess>absent());

    BuildRule rule3 = new FakeBuildRule(
        BuildTargetFactory.newInstance("//fake:rule3"),
        resolver);
    ruleToResult.put(rule3, Optional.of(new BuildRuleSuccess(rule3, Type.FETCHED_FROM_CACHE)));
  }

  @Test
  public void testGenerateBuildReportForConsole() {
    String expectedReport =
        "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule1 " +
            "BUILT_LOCALLY buck-out/gen/fake/rule1.txt\n" +
        "\u001B[1m\u001B[41m\u001B[37mFAIL\u001B[0m //fake:rule2\n" +
        "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule3 FETCHED_FROM_CACHE\n";
    String observedReport = new BuildReport(ruleToResult).generateForConsole(Ansi.forceTty());
    assertEquals(expectedReport, observedReport);
  }

  @Test
  public void testGenerateJsonBuildReport() throws IOException {
    String expectedReport = Joiner.on('\n').join(
        "{",
        "  \"results\" : {",
        "    \"//fake:rule1\" : {",
        "      \"success\" : true,",
        "      \"type\" : \"BUILT_LOCALLY\",",
        "      \"output\" : \"buck-out/gen/fake/rule1.txt\"",
        "    },",
        "    \"//fake:rule2\" : {",
        "      \"success\" : false",
        "    },",
        "    \"//fake:rule3\" : {",
        "      \"success\" : true,",
        "      \"type\" : \"FETCHED_FROM_CACHE\",",
        "      \"output\" : null",
        "    }",
        "  }",
        "}");
    String observedReport = new BuildReport(ruleToResult).generateJsonBuildReport();
    assertEquals(expectedReport, observedReport);
  }
}

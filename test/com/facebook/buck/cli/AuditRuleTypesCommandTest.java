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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class AuditRuleTypesCommandTest {

  private static final List<String> KNOWN_RULE_TYPES =
      Arrays.asList("another_build_rule", "some_build_rule");

  private static class SomeBuildRuleDescription
      implements DescriptionWithTargetGraph<BuildRuleArg> {

    @Override
    public Class<BuildRuleArg> getConstructorArgType() {
      return BuildRuleArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        BuildRuleArg args) {
      return null;
    }
  }

  private static class AnotherBuildRuleDescription extends SomeBuildRuleDescription {}

  private static final ImmutableList<Description<?>> DESCRIPTIONS =
      ImmutableList.of(new SomeBuildRuleDescription(), new AnotherBuildRuleDescription());

  @Before
  public void setUp() {}

  @Test
  public void testBuildInfoPrintedInJsonFormat() throws Exception {

    TestConsole console = new TestConsole();

    AuditRuleTypesCommand.collectAndDumpBuildRuleTypesInformation(
        console,
        KnownNativeRuleTypes.of(DESCRIPTIONS, ImmutableList.of(), ImmutableList.of()),
        true);

    @SuppressWarnings("PMD.LooseCoupling")
    List<String> buildRuleTypes =
        ObjectMappers.readValue(console.getTextWrittenToStdOut(), ArrayList.class);

    assertEquals(KNOWN_RULE_TYPES, buildRuleTypes);
  }

  @Test
  public void testBuildInfoPrintedInRawFormat() throws Exception {

    TestConsole console = new TestConsole();

    AuditRuleTypesCommand.collectAndDumpBuildRuleTypesInformation(
        console,
        KnownNativeRuleTypes.of(DESCRIPTIONS, ImmutableList.of(), ImmutableList.of()),
        false);

    List<String> buildRuleTypes =
        Splitter.on(System.lineSeparator())
            .omitEmptyStrings()
            .splitToList(console.getTextWrittenToStdOut());

    assertEquals(KNOWN_RULE_TYPES, buildRuleTypes);
  }
}

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

package com.facebook.buck.core.test.rule;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.test.rule.coercer.TestRunnerSpecCoercer;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.macros.AbsoluteOutputMacro;
import com.facebook.buck.rules.macros.AbsoluteOutputMacroExpander;
import com.facebook.buck.rules.macros.RuleWithSupplementaryOutput;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Test;

public class TestRunnerSpecCoercionSerializationTest {

  private final BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:test");
  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellPathResolver cellPathResolver = TestCellBuilder.createCellRoots(filesystem);

  private StringWithMacrosConverter getConverter(ActionGraphBuilder graphBuilder, BuildRule rule) {
    return StringWithMacrosConverter.of(
        rule.getBuildTarget(),
        cellPathResolver.getCellNameResolver(),
        graphBuilder,
        ImmutableList.of(AbsoluteOutputMacroExpander.INSTANCE));
  }

  @Test
  public void coercesStringWithMacrosSerializesProperly() throws IOException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    RuleWithSupplementaryOutput rule = new RuleWithSupplementaryOutput(buildTarget, filesystem);
    graphBuilder.addToIndex(rule);

    ImmutableTestRunnerSpec spec =
        ImmutableTestRunnerSpec.of(
            ImmutableMap.of(
                StringWithMacrosUtils.format("a"),
                ImmutableTestRunnerSpec.of(
                    ImmutableList.of(
                        ImmutableTestRunnerSpec.of(StringWithMacrosUtils.format("foo")),
                        ImmutableTestRunnerSpec.of(
                            ImmutableMap.of(
                                StringWithMacrosUtils.format("a_nest"),
                                ImmutableTestRunnerSpec.of(
                                    StringWithMacrosUtils.format(
                                        "nested %s", AbsoluteOutputMacro.of("bar"))))))),
                StringWithMacrosUtils.format("some %s", AbsoluteOutputMacro.of("foo")),
                ImmutableTestRunnerSpec.of(StringWithMacrosUtils.format("faz")),
                StringWithMacrosUtils.format("int"),
                ImmutableTestRunnerSpec.of(1),
                StringWithMacrosUtils.format("double"),
                ImmutableTestRunnerSpec.of(2.4),
                StringWithMacrosUtils.format("boolean"),
                ImmutableTestRunnerSpec.of(true)));

    CoercedTestRunnerSpec coercedSpec =
        TestRunnerSpecCoercer.coerce(spec, getConverter(graphBuilder, rule));
    ImmutableMap<String, Object> expectedSpec =
        ImmutableMap.of(
            "a",
            ImmutableList.of(
                "foo",
                ImmutableMap.of(
                    "a_nest",
                    "nested "
                        + graphBuilder
                            .getSourcePathResolver()
                            .getAbsolutePath(rule.getSourcePathToSupplementaryOutput("bar")))),
            "some "
                + graphBuilder
                    .getSourcePathResolver()
                    .getAbsolutePath(rule.getSourcePathToSupplementaryOutput("foo")),
            "faz",
            "int",
            1,
            "double",
            2.4,
            "boolean",
            true);

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    JsonGenerator generator = ObjectMappers.createGenerator(outputStream).useDefaultPrettyPrinter();
    coercedSpec.serialize(generator, graphBuilder.getSourcePathResolver());
    generator.close();
    assertEquals(
        ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValueAsString(expectedSpec),
        outputStream.toString(Charsets.UTF_8.name()));
  }
}

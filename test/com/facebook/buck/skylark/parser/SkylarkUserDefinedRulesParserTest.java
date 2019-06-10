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
package com.facebook.buck.skylark.parser;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

public class SkylarkUserDefinedRulesParserTest {
  private SkylarkProjectBuildFileParser parser;
  private ProjectWorkspace workspace;
  private ProjectFilesystem projectFilesystem;
  private SkylarkFilesystem skylarkFilesystem;
  private KnownRuleTypesProvider knownRuleTypesProvider;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();
  private Cell cell;

  private void setupWorkspace(String scenario) throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp.getRoot());
    workspace.setUp();
    projectFilesystem = new FakeProjectFilesystem(tmp.getRoot());
    skylarkFilesystem = SkylarkFilesystem.using(projectFilesystem);
    cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    knownRuleTypesProvider = TestKnownRuleTypesProvider.create(pluginManager);
    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));
  }

  private SkylarkProjectBuildFileParser createParser(EventHandler eventHandler) {
    return SkylarkProjectBuildFileParserTestUtils.createParserWithOptions(
        skylarkFilesystem,
        eventHandler,
        SkylarkProjectBuildFileParserTestUtils.getDefaultParserOptions(cell, knownRuleTypesProvider)
            .setEnableUserDefinedRules(true)
            .build());
  }

  private Map<String, Object> getSingleRule(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    return SkylarkProjectBuildFileParserTestUtils.getSingleRule(parser, buildFile);
  }

  private void assertParserFails(
      EventCollector eventCollector,
      SkylarkProjectBuildFileParser parser,
      Path buildFile,
      String substring)
      throws IOException, InterruptedException {

    thrown.expect(BuildFileParseException.class);

    try {
      parser.getBuildFileManifest(buildFile);

    } catch (BuildFileParseException e) {
      Event event = eventCollector.iterator().next();
      assertEquals(EventKind.ERROR, event.getKind());
      assertThat(event.getMessage(), containsString(substring));
      throw e;
    }
  }

  @Test
  public void enablesLabelObjectIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("label_exported");
    Path buildFile = projectFilesystem.resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    assertEquals("echo target > $OUT", getSingleRule(buildFile).get("cmd"));
  }

  @Test
  public void enablesAttrsModuleIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr_exported");
    Path buildFile = projectFilesystem.resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getBuildFileManifest(buildFile);
  }

  @Test
  public void enablesAttrsIntIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr_int_exported");
    Path buildFile = projectFilesystem.resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));
    parser.getBuildFileManifest(buildFile);
  }

  @Test
  public void attrsIntThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr_int_throws_on_invalid");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected type 'int' but got type 'string' instead");
  }

  @Test
  public void enablesAttrsStringIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr_int_exported");
    Path buildFile = projectFilesystem.resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getBuildFileManifest(buildFile);
  }

  @Test
  public void attrsStringThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr_string_throws_on_invalid");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected type 'string' but got type 'int' instead");
  }

  @Test
  public void ruleFailsIfWrongImplTypeProvided() throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_impl_type");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector,
        parser,
        buildFile,
        "expected value of type 'function' for parameter 'implementation'");
  }

  @Test
  public void ruleFailsIfWrongAttrTypeProvided() throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_attr_type");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected value of type 'dict' for parameter 'attrs'");
  }

  @Test
  public void ruleFailsIfImplementationTakesZeroArgs() throws IOException, InterruptedException {
    setupWorkspace("rule_with_zero_arg_impl");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector,
        parser,
        buildFile,
        "Implementation function '_impl' must accept a single 'ctx' argument. Accepts 0 arguments");
  }

  @Test
  public void ruleFailsIfImplementationTakesMoreThanOneArg()
      throws IOException, InterruptedException {
    setupWorkspace("rule_with_multi_arg_impl");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector,
        parser,
        buildFile,
        "Implementation function '_impl' must accept a single 'ctx' argument. Accepts 2 arguments");
  }

  @Test
  public void failsIfAttributeDictValueIsNotAnAttrObject()
      throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_attr_value_type");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector,
        parser,
        buildFile,
        "expected type 'AttributeHolder' for 'attrs keyword of rule()' value but got type 'int' instead");
  }

  @Test
  public void failsIfAttributeIsNotAValidPythonParameterName()
      throws IOException, InterruptedException {
    setupWorkspace("rule_with_invalid_attr_name");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "Attribute name 'foo-bar' is not a valid identifier");
  }

  @Test
  public void failsIfAttributeNameIsEmpty() throws IOException, InterruptedException {
    setupWorkspace("rule_with_empty_attr_name");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "Attribute name '' is not a valid identifier");
  }

  @Test
  public void failsIfAttributeDuplicatesBuiltInName() throws IOException, InterruptedException {

    setupWorkspace("rule_with_shadowing_attr_name");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector,
        parser,
        buildFile,
        "Provided attr 'name' shadows implicit attribute. Please remove it.");
  }

  @Test
  public void acceptsAutomaticallyAddedAttributes() throws IOException, InterruptedException {
    setupWorkspace("rule_with_builtin_arguments");

    // TODO: Change this to visibility when that is added to SkylarkUserDefinedRule
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");

    ImmutableMap<String, ImmutableMap<String, Object>> expected =
        ImmutableMap.of(
            "target1",
            ImmutableMap.of(
                "name",
                "target1",
                "buck.base_path",
                "subdir",
                "buck.type",
                "@//subdir:foo.bzl:some_rule",
                "attr1",
                3,
                "attr2",
                2));

    parser = createParser(eventCollector);

    BuildFileManifest rules = parser.getBuildFileManifest(buildFile);

    assertEquals(expected, rules.getTargets());
  }

  @Test
  public void addsRuleToParserContextWhenUserDefinedRuleCallableIsCalled()
      throws IOException, InterruptedException {
    setupWorkspace("rule_with_builtin_arguments_and_macro");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");

    ImmutableMap<String, ImmutableMap<String, Object>> expected =
        ImmutableMap.of(
            "target1",
            ImmutableMap.of(
                "name",
                "target1",
                "buck.base_path",
                "subdir",
                "buck.type",
                "@//subdir:foo.bzl:some_rule",
                "attr1",
                3,
                "attr2",
                2),
            "target2",
            ImmutableMap.of(
                "name",
                "target2",
                "buck.base_path",
                "subdir",
                "buck.type",
                "@//subdir:foo.bzl:some_rule",
                "attr1",
                4,
                "attr2",
                5));

    parser = createParser(eventCollector);

    BuildFileManifest rules = parser.getBuildFileManifest(buildFile);

    assertEquals(expected, rules.getTargets());
  }
}

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

package com.facebook.buck.skylark.parser;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.starlark.knowntypes.KnownUserDefinedRuleTypes;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.UserDefinedRulesState;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
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
  private Cells cell;

  private void setupWorkspace(String scenario) throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp.getRoot());
    workspace.setUp();
    projectFilesystem = new FakeProjectFilesystem(CanonicalCellName.rootCell(), tmp.getRoot());
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
        SkylarkProjectBuildFileParserTestUtils.getDefaultParserOptions(
                cell.getRootCell(), knownRuleTypesProvider)
            .setUserDefinedRulesState(UserDefinedRulesState.of(true))
            .build(),
        knownRuleTypesProvider,
        cell.getRootCell());
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
      parser.getManifest(buildFile);

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
    setupWorkspace("attr");
    Path buildFile = projectFilesystem.resolve("exported").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(buildFile);
  }

  @Test
  public void enablesAttrsIntIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");
    Path buildFile = projectFilesystem.resolve("int").resolve("well_formed").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));
    parser.getManifest(buildFile);
  }

  @Test
  public void attrsIntThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("int").resolve("malformed").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected type 'int' but got type 'string' instead");
  }

  @Test
  public void enablesAttrsBoolIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");
    Path buildFile = projectFilesystem.resolve("bool").resolve("well_formed").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));
    parser.getManifest(buildFile);
  }

  @Test
  public void attrsBoolThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("bool").resolve("malformed").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(eventCollector, parser, buildFile, "expected value of type 'bool'");
  }

  @Test
  public void enablesAttrsStringIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");
    Path buildFile = projectFilesystem.resolve("string").resolve("well_formed").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(buildFile);
  }

  @Test
  public void attrsStringThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("string").resolve("malformed").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected type 'string' but got type 'int' instead");
  }

  @Test
  public void enablesAttrsSourceListIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");
    Path buildFile =
        projectFilesystem.resolve("source_list").resolve("well_formed").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(buildFile);
  }

  @Test
  public void attrsSourceListThrowsExceptionOnInvalidDefaultValueType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile =
        projectFilesystem.resolve("source_list").resolve("malformed_default").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected value of type 'sequence of strings'");
  }

  @Test
  public void enablesAttrsSourceIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");
    Path buildFile = projectFilesystem.resolve("source").resolve("well_formed").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(buildFile);
  }

  @Test
  public void enablesAttrsDepIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");
    Path buildFile = projectFilesystem.resolve("dep").resolve("well_formed").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(buildFile);
  }

  @Test
  public void attrsSourceThrowsExceptionOnInvalidDefaultValueType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile =
        projectFilesystem.resolve("source").resolve("malformed_default").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(eventCollector, parser, buildFile, "expected value of type 'string");
  }

  @Test
  public void attrsDepThrowsExceptionOnInvalidDefaultValueType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("dep").resolve("malformed_default").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(eventCollector, parser, buildFile, "expected value of type 'string");
  }

  @Test
  public void attrsDepThrowsExceptionOnInvalidProvidersType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile =
        projectFilesystem.resolve("dep").resolve("malformed_providers").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(eventCollector, parser, buildFile, "expected type 'Provider'");
  }

  @Test
  public void enablesAttrsDepListIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");
    Path buildFile = projectFilesystem.resolve("dep_list").resolve("well_formed").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(buildFile);
  }

  @Test
  public void attrsDepListThrowsExceptionOnInvalidDefaultValueType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile =
        projectFilesystem.resolve("dep_list").resolve("malformed_default").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected value of type 'sequence of strings'");
  }

  @Test
  public void attrsDepListThrowsExceptionOnInvalidProvidersType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile =
        projectFilesystem.resolve("dep_list").resolve("malformed_providers").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(eventCollector, parser, buildFile, "expected type 'Provider'");
  }

  @Test
  public void enablesAttrsStringListIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");
    Path buildFile =
        projectFilesystem.resolve("string_list").resolve("well_formed").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(buildFile);
  }

  @Test
  public void attrsStringListThrowsExceptionOnInvalidTypes()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("string_list").resolve("malformed").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected value of type 'sequence of strings'");
  }

  @Test
  public void enablesAttrsIntListIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");
    Path buildFile = projectFilesystem.resolve("int_list").resolve("well_formed").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(buildFile);
  }

  @Test
  public void attrsIntListThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("int_list").resolve("malformed").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected value of type 'sequence of ints'");
  }

  @Test
  public void enablesAttrsOutputIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");
    Path buildFile = projectFilesystem.resolve("output").resolve("well_formed").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(buildFile);
  }

  @Test
  public void attrsOutputThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("output").resolve("malformed").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected value of type 'string or NoneType'");
  }

  @Test
  public void attrsOutputHandlesAbsentDefault() throws IOException, InterruptedException {
    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path noDefault = projectFilesystem.resolve("output").resolve("no_default").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector,
        parser,
        noDefault,
        "output attributes must have a default value, or be mandatory");
  }

  @Test
  public void enablesAttrsOutputListIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");
    Path buildFile =
        projectFilesystem.resolve("output_list").resolve("well_formed").resolve("BUCK");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(buildFile);
  }

  @Test
  public void attrsOutputListThrowsExceptionOnInvalidTypes()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("output_list").resolve("malformed").resolve("BUCK");

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected value of type 'sequence of strings'");
  }

  @Test
  public void ruleFailsIfWrongImplTypeProvided() throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_types");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve(Paths.get("impl_type", "subdir", "BUCK"));

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector,
        parser,
        buildFile,
        "expected value of type 'function' for parameter 'implementation'");
  }

  @Test
  public void ruleFailsIfWrongAttrTypeProvided() throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_types");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve(Paths.get("attr_type", "subdir", "BUCK"));

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
  public void failsIfInferRunInfoIsNotABoolean() throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_types");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    Path buildFile = projectFilesystem.resolve(Paths.get("infer_run_info_type", "subdir", "BUCK"));

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector,
        parser,
        buildFile,
        "expected value of type 'bool' for parameter 'infer_run_info'");
  }

  @Test
  public void failsIfTestIsNotBoolean() throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_types");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    Path buildFile = projectFilesystem.resolve(Paths.get("test_type", "subdir", "BUCK"));

    parser = createParser(eventCollector);

    assertParserFails(
        eventCollector, parser, buildFile, "expected value of type 'bool' for parameter 'test'");
  }

  @Test
  public void testAttributesAreAvailableForTestRules() throws IOException, InterruptedException {
    setupWorkspace("rule_with_contacts");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    Path buildFile = projectFilesystem.resolve(Paths.get("subdir", "BUCK"));

    parser = createParser(eventCollector);

    BuildFileManifest manifest = parser.getManifest(buildFile);
    assertEquals(
        ImmutableList.of("foo@example.com", "bar@example.com"),
        manifest.getTargets().get("target1").get("contacts"));
  }

  @Test
  public void failsIfAttributeDictValueIsNotAnAttrObject()
      throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_types");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    Path buildFile = projectFilesystem.resolve(Paths.get("attr_value_type", "subdir", "BUCK"));

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
            ImmutableMap.<String, Object>builder()
                .put("name", "target1")
                .put("buck.base_path", "subdir")
                .put("buck.type", "//subdir:foo.bzl:some_rule")
                .put("attr1", 3)
                .put("attr2", 2)
                .put("licenses", ImmutableSortedSet.of())
                .put("labels", ImmutableSortedSet.of())
                .put("default_target_platform", Optional.empty())
                .put("compatible_with", ImmutableList.of())
                .build());

    parser = createParser(eventCollector);

    BuildFileManifest rules = parser.getManifest(buildFile);

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
            ImmutableMap.<String, Object>builder()
                .put("name", "target1")
                .put("buck.base_path", "subdir")
                .put("buck.type", "//subdir:foo.bzl:some_rule")
                .put("attr1", 3)
                .put("attr2", 2)
                .put("licenses", ImmutableSortedSet.of())
                .put("labels", ImmutableSortedSet.of())
                .put("default_target_platform", Optional.empty())
                .put("compatible_with", ImmutableList.of())
                .build(),
            "target2",
            ImmutableMap.<String, Object>builder()
                .put("name", "target2")
                .put("buck.base_path", "subdir")
                .put("buck.type", "//subdir:foo.bzl:some_rule")
                .put("attr1", 4)
                .put("attr2", 5)
                .put("licenses", ImmutableSortedSet.of())
                .put("labels", ImmutableSortedSet.of())
                .put("default_target_platform", Optional.empty())
                .put("compatible_with", ImmutableList.of())
                .build());

    parser = createParser(eventCollector);

    BuildFileManifest rules = parser.getManifest(buildFile);

    assertEquals(expected, rules.getTargets());
  }

  @Test
  public void clearsKnownUserDefinedRuleTypesWhenExtensionChanges()
      throws IOException, InterruptedException {
    setupWorkspace("basic_rule");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");
    String replacement =
        workspace.getFileContents(projectFilesystem.resolve("subdir").resolve("new_defs.bzl"));

    parser = createParser(eventCollector);

    String rule1Identifier = "//subdir:defs.bzl:some_rule";
    String rule2Identifier = "//subdir:defs.bzl:some_other_rule";

    KnownUserDefinedRuleTypes knownUserDefinedRuleTypes =
        knownRuleTypesProvider.getUserDefinedRuleTypes(cell.getRootCell());

    assertNull(knownUserDefinedRuleTypes.getRule(rule1Identifier));
    assertNull(knownUserDefinedRuleTypes.getRule(rule2Identifier));

    BuildFileManifest rules = parser.getManifest(buildFile);
    assertEquals(2, rules.getTargets().size());

    SkylarkUserDefinedRule rule1 = knownUserDefinedRuleTypes.getRule(rule1Identifier);
    SkylarkUserDefinedRule rule2 = knownUserDefinedRuleTypes.getRule(rule2Identifier);

    assertNotNull(rule1);
    assertNotNull(rule2);
    assertEquals(rule1Identifier, rule1.getName());
    assertEquals(rule2Identifier, rule2.getName());

    // write 'new_defs.bzl' (which doesn't have `some_other_rule`) to 'defs.bzl' so that
    // we properly test invalidation logic when defs.bzl changes
    workspace.writeContentsToPath(
        replacement, projectFilesystem.resolve("subdir").resolve("defs.bzl"));
    parser = createParser(eventCollector);
    rules = parser.getManifest(buildFile);
    assertEquals(2, rules.getTargets().size());

    rule1 = knownUserDefinedRuleTypes.getRule(rule1Identifier);
    rule2 = knownUserDefinedRuleTypes.getRule(rule2Identifier);

    assertNotNull(rule1);
    assertNull(rule2);
    assertEquals(rule1Identifier, rule1.getName());
  }

  @Test
  public void addsKnownUserDefinedRuleTypesWhenRuleIsExported()
      throws IOException, InterruptedException {
    setupWorkspace("basic_rule");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");
    parser = createParser(eventCollector);

    String rule1Identifier = "//subdir:defs.bzl:some_rule";
    String rule2Identifier = "//subdir:defs.bzl:some_other_rule";

    KnownUserDefinedRuleTypes knownUserDefinedRuleTypes =
        knownRuleTypesProvider.getUserDefinedRuleTypes(cell.getRootCell());
    assertNull(knownUserDefinedRuleTypes.getRule(rule1Identifier));
    assertNull(knownUserDefinedRuleTypes.getRule(rule2Identifier));

    BuildFileManifest rules = parser.getManifest(buildFile);
    assertEquals(2, rules.getTargets().size());

    SkylarkUserDefinedRule rule1 = knownUserDefinedRuleTypes.getRule(rule1Identifier);
    SkylarkUserDefinedRule rule2 = knownUserDefinedRuleTypes.getRule(rule2Identifier);

    assertNotNull(rule1);
    assertNotNull(rule2);
    assertEquals(rule1Identifier, rule1.getName());
    assertEquals(rule2Identifier, rule2.getName());
    assertEquals(Integer.class, rule1.getAllParamInfo().get("attr1").getResultClass());
    assertEquals(Integer.class, rule2.getAllParamInfo().get("attr2").getResultClass());
  }

  @Test
  public void builtInProvidersAreExportedWhenEnabled() throws IOException, InterruptedException {
    setupWorkspace("builtin_providers_exported");
    Path buildFile = projectFilesystem.resolve("subdir").resolve("BUCK");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    assertEquals("target1", getSingleRule(buildFile).get("name"));
    Iterables.find(
        collector,
        e -> e.getKind() == EventKind.DEBUG && e.getMessage().contains("in bzl: DefaultInfo("));
  }

  @Test
  public void providerFunctionFailsIfInvalidFieldNameProvided()
      throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");
    Path buildFile = projectFilesystem.resolve("invalid_field").resolve("BUCK");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    parser.getManifest(buildFile);
  }

  @Test
  public void providerFunctionFailsIfInvalidTypeProvidedForFields()
      throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");
    Path buildFile = projectFilesystem.resolve("invalid_type").resolve("BUCK");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    parser.getManifest(buildFile);
  }

  @Test
  public void providerFunctionFailsIfInvalidListType() throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");
    Path buildFile = projectFilesystem.resolve("invalid_list_type").resolve("BUCK");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    parser.getManifest(buildFile);
  }

  @Test
  public void providerFunctionFailsIfInvalidDictType() throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");
    Path buildFile = projectFilesystem.resolve("invalid_dict_type").resolve("BUCK");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    parser.getManifest(buildFile);
  }

  @Test
  public void providerInfoFromProviderFunctionHasCorrectFields()
      throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");
    Path buildFile = projectFilesystem.resolve("valid_provider").resolve("BUCK");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    parser.getManifest(buildFile);
  }

  @Test
  public void providerCannotBeUsedIfNotAssigned() throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");
    Path buildFile = projectFilesystem.resolve("not_assigned").resolve("BUCK");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    // TODO(T48080142): When we wrap up all of these validation errors into something human
    // friendly, this will become a build file error
    thrown.expect(NullPointerException.class);
    parser.getManifest(buildFile);
  }

  @Test
  public void testRulesMustEndInTest() throws IOException, InterruptedException {
    setupWorkspace("basic_rule");
    Path buildFile = projectFilesystem.resolve("non_test_rule_name").resolve("BUCK");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Only rules with `test = True` may end with `_test`");
    parser.getManifest(buildFile);
  }

  @Test
  public void nonTestRulesMustNotEndInTest() throws IOException, InterruptedException {

    setupWorkspace("basic_rule");
    Path buildFile = projectFilesystem.resolve("test_rule_name").resolve("BUCK");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Rules with `test = True` must end with `_test`");
    parser.getManifest(buildFile);
  }
}

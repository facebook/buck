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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.starlark.eventhandler.EventCollector;
import com.facebook.buck.core.starlark.eventhandler.EventHandler;
import com.facebook.buck.core.starlark.eventhandler.EventKind;
import com.facebook.buck.core.starlark.eventhandler.PrintingEventHandler;
import com.facebook.buck.core.starlark.knowntypes.KnownUserDefinedRuleTypes;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.UserDefinedRulesState;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

public class SkylarkUserDefinedRulesParserTest {
  private SkylarkProjectBuildFileParser parser;
  private ProjectWorkspace workspace;
  private ProjectFilesystem projectFilesystem;
  private KnownRuleTypesProvider knownRuleTypesProvider;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();
  private Cells cell;

  private void setupWorkspace(String scenario) throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp.getRoot());
    workspace.setUp();
    projectFilesystem = new FakeProjectFilesystem(tmp.getRoot());
    cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    knownRuleTypesProvider = TestKnownRuleTypesProvider.create(pluginManager);
    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));
  }

  private SkylarkProjectBuildFileParser createParser(EventHandler eventHandler) {
    return SkylarkProjectBuildFileParserTestUtils.createParserWithOptions(
        eventHandler,
        SkylarkProjectBuildFileParserTestUtils.getDefaultParserOptions(
                cell.getRootCell(), knownRuleTypesProvider)
            .setUserDefinedRulesState(UserDefinedRulesState.of(true))
            .build(),
        knownRuleTypesProvider,
        cell.getRootCell());
  }

  private RawTargetNode getSingleRule(AbsPath buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    ForwardRelPath buildFileRel =
        ForwardRelPath.ofRelPath(MorePaths.relativize(projectFilesystem.getRootPath(), buildFile));
    return SkylarkProjectBuildFileParserTestUtils.getSingleRule(parser, buildFileRel);
  }

  private void assertParserFails(
      SkylarkProjectBuildFileParser parser, ForwardRelPath buildFile, String substring)
      throws IOException, InterruptedException {

    thrown.expect(BuildFileParseException.class);

    try {
      parser.getManifest(buildFile);
    } catch (BuildFileParseException e) {
      assertThat(e.getMessage(), containsString(substring));
      throw e;
    }
  }

  @Test
  public void enablesAttrsModuleIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(ForwardRelPath.of("exported/BUCK"));
  }

  @Test
  public void enablesAttrsIntIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));
    parser.getManifest(ForwardRelPath.of("int/well_formed/BUCK"));
  }

  @Test
  public void attrsIntThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser, ForwardRelPath.of("int/malformed/BUCK"), "got element of type string, want int");
  }

  @Test
  public void enablesAttrsBoolIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));
    parser.getManifest(ForwardRelPath.of("bool/well_formed/BUCK"));
  }

  @Test
  public void attrsBoolThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("bool/malformed/BUCK"),
        "got value of type 'string', want 'bool'");
  }

  @Test
  public void enablesAttrsStringIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(ForwardRelPath.of("string/well_formed/BUCK"));
  }

  @Test
  public void attrsStringThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser, ForwardRelPath.of("string/malformed/BUCK"), "got element of type int, want string");
  }

  @Test
  public void enablesAttrsSourceListIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(ForwardRelPath.of("source_list/well_formed/BUCK"));
  }

  @Test
  public void attrsSourceListThrowsExceptionOnInvalidDefaultValueType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("source_list/malformed_default/BUCK"),
        "parameter 'default' got value of type 'int', want 'list'");
  }

  @Test
  public void enablesAttrsSourceIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(ForwardRelPath.of("source/well_formed/BUCK"));
  }

  @Test
  public void enablesAttrsDepIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(ForwardRelPath.of("dep/well_formed/BUCK"));
  }

  @Test
  public void attrsSourceThrowsExceptionOnInvalidDefaultValueType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser, ForwardRelPath.of("source/malformed_default/BUCK"), "want 'string or NoneType'");
  }

  @Test
  public void attrsDepThrowsExceptionOnInvalidDefaultValueType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("dep/malformed_default/BUCK"),
        "got value of type 'int', want 'string or NoneType'");
  }

  @Test
  public void attrsDepThrowsExceptionOnInvalidProvidersType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("dep/malformed_providers/BUCK"),
        "expected a sequence of 'Provider'");
  }

  @Test
  public void enablesAttrsDepListIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(ForwardRelPath.of("dep_list/well_formed/BUCK"));
  }

  @Test
  public void attrsDepListThrowsExceptionOnInvalidDefaultValueType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("dep_list/malformed_default/BUCK"),
        "got value of type 'int', want 'list'");
  }

  @Test
  public void attrsDepListThrowsExceptionOnInvalidProvidersType()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("dep_list/malformed_providers/BUCK"),
        "expected a sequence of 'Provider'");
  }

  @Test
  public void enablesAttrsStringListIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(ForwardRelPath.of("string_list/well_formed/BUCK"));
  }

  @Test
  public void attrsStringListThrowsExceptionOnInvalidTypes()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(parser, ForwardRelPath.of("string_list/malformed/BUCK"), "want 'list'");
  }

  @Test
  public void enablesAttrsIntListIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(ForwardRelPath.of("int_list/well_formed/BUCK"));
  }

  @Test
  public void attrsIntListThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("int_list/malformed/BUCK"),
        "parameter 'default' got value of type 'int', want 'list'");
  }

  @Test
  public void enablesAttrsOutputIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(ForwardRelPath.of("output/well_formed/BUCK"));
  }

  @Test
  public void attrsOutputThrowsExceptionOnInvalidTypes() throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser, ForwardRelPath.of("output/malformed/BUCK"), "want 'NoneType or string'");
  }

  @Test
  public void attrsOutputHandlesAbsentDefault() throws IOException, InterruptedException {
    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("output/no_default/BUCK"),
        "output attributes must have a default value, or be mandatory");
  }

  @Test
  public void enablesAttrsOutputListIfConfigured() throws IOException, InterruptedException {
    setupWorkspace("attr");

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getManifest(ForwardRelPath.of("output_list/well_formed/BUCK"));
  }

  @Test
  public void attrsOutputListThrowsExceptionOnInvalidTypes()
      throws IOException, InterruptedException {

    setupWorkspace("attr");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("output_list/malformed/BUCK"),
        "got value of type 'int', want 'list'");
  }

  @Test
  public void ruleFailsIfWrongImplTypeProvided() throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_types");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("impl_type/subdir/BUCK"),
        "parameter 'implementation' got value of type 'string', want 'function'");
  }

  @Test
  public void ruleFailsIfWrongAttrTypeProvided() throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_types");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser, ForwardRelPath.of("attr_type/subdir/BUCK"), "got value of type 'int', want 'dict'");
  }

  @Test
  public void ruleFailsIfImplementationTakesZeroArgs() throws IOException, InterruptedException {
    setupWorkspace("rule_with_zero_arg_impl");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("subdir/BUCK"),
        "Implementation function '_impl' must accept a single 'ctx' argument. Accepts 0 arguments");
  }

  @Test
  public void ruleFailsIfImplementationTakesMoreThanOneArg()
      throws IOException, InterruptedException {
    setupWorkspace("rule_with_multi_arg_impl");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("subdir/BUCK"),
        "Implementation function '_impl' must accept a single 'ctx' argument. Accepts 2 arguments");
  }

  @Test
  public void failsIfInferRunInfoIsNotABoolean() throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_types");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("infer_run_info_type/subdir/BUCK"),
        "parameter 'infer_run_info' got value of type 'int', want 'bool'");
  }

  @Test
  public void failsIfTestIsNotBoolean() throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_types");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("test_type/subdir/BUCK"),
        "parameter 'test' got value of type 'int', want 'bool'");
  }

  @Test
  public void testAttributesAreAvailableForTestRules() throws IOException, InterruptedException {
    setupWorkspace("rule_with_contacts");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    BuildFileManifest manifest = parser.getManifest(ForwardRelPath.of("subdir/BUCK"));
    assertEquals(
        ImmutableList.of("foo@example.com", "bar@example.com"),
        manifest.getTargets().get("target1").getBySnakeCase("contacts"));
  }

  @Test
  public void failsIfAttributeDictValueIsNotAnAttrObject()
      throws IOException, InterruptedException {
    setupWorkspace("rule_with_wrong_types");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("attr_value_type/subdir/BUCK"),
        "got dict<string, int> for 'attrs keyword of rule()', want dict<string, AttributeHolder>");
  }

  @Test
  public void failsIfAttributeIsNotAValidPythonParameterName()
      throws IOException, InterruptedException {
    setupWorkspace("rule_with_invalid_attr_name");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("subdir/BUCK"),
        "Attribute name 'foo-bar' is not a valid identifier");
  }

  @Test
  public void failsIfAttributeNameIsEmpty() throws IOException, InterruptedException {
    setupWorkspace("rule_with_empty_attr_name");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser, ForwardRelPath.of("subdir/BUCK"), "Attribute name '' is not a valid identifier");
  }

  @Test
  public void failsIfAttributeDuplicatesBuiltInName() throws IOException, InterruptedException {

    setupWorkspace("rule_with_shadowing_attr_name");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    parser = createParser(eventCollector);

    assertParserFails(
        parser,
        ForwardRelPath.of("subdir/BUCK"),
        "Provided attr 'name' shadows implicit attribute. Please remove it.");
  }

  @Test
  public void acceptsAutomaticallyAddedAttributes() throws IOException, InterruptedException {
    setupWorkspace("rule_with_builtin_arguments");

    // TODO: Change this to visibility when that is added to SkylarkUserDefinedRule
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    ImmutableMap<String, RawTargetNode> expected =
        ImmutableMap.of(
            "target1",
            RawTargetNode.copyOf(
                ForwardRelPath.of("subdir"),
                "//subdir:foo.bzl:some_rule",
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableMap.<String, Object>builder()
                    .put("name", "target1")
                    .put("attr1", 3)
                    .put("attr2", 2)
                    .put("licenses", ImmutableSortedSet.of())
                    .put("labels", ImmutableSortedSet.of())
                    .put("contacts", ImmutableSortedSet.of())
                    .put("default_target_platform", Optional.empty())
                    .put("default_host_platform", Optional.empty())
                    .put("compatible_with", ImmutableList.of())
                    .build()));

    parser = createParser(eventCollector);

    BuildFileManifest rules = parser.getManifest(ForwardRelPath.of("subdir/BUCK"));

    assertEquals(expected, rules.getTargets());
  }

  @Test
  public void addsRuleToParserContextWhenUserDefinedRuleCallableIsCalled()
      throws IOException, InterruptedException {
    setupWorkspace("rule_with_builtin_arguments_and_macro");

    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));

    ImmutableMap<String, RawTargetNode> expected =
        ImmutableMap.of(
            "target1",
            RawTargetNode.copyOf(
                ForwardRelPath.of("subdir"),
                "//subdir:foo.bzl:some_rule",
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableMap.<String, Object>builder()
                    .put("name", "target1")
                    .put("attr1", 3)
                    .put("attr2", 2)
                    .put("licenses", ImmutableSortedSet.of())
                    .put("labels", ImmutableSortedSet.of())
                    .put("contacts", ImmutableSortedSet.of())
                    .put("default_target_platform", Optional.empty())
                    .put("default_host_platform", Optional.empty())
                    .put("compatible_with", ImmutableList.of())
                    .build()),
            "target2",
            RawTargetNode.copyOf(
                ForwardRelPath.of("subdir"),
                "//subdir:foo.bzl:some_rule",
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableMap.<String, Object>builder()
                    .put("name", "target2")
                    .put("attr1", 4)
                    .put("attr2", 5)
                    .put("licenses", ImmutableSortedSet.of())
                    .put("labels", ImmutableSortedSet.of())
                    .put("contacts", ImmutableSortedSet.of())
                    .put("default_target_platform", Optional.empty())
                    .put("default_host_platform", Optional.empty())
                    .put("compatible_with", ImmutableList.of())
                    .build()));

    parser = createParser(eventCollector);

    BuildFileManifest rules = parser.getManifest(ForwardRelPath.of("subdir/BUCK"));

    assertEquals(expected, rules.getTargets());
  }

  @Test
  public void clearsKnownUserDefinedRuleTypesWhenExtensionChanges()
      throws IOException, InterruptedException {
    setupWorkspace("basic_rule");
    EventCollector eventCollector = new EventCollector(EnumSet.allOf(EventKind.class));
    String replacement =
        workspace.getFileContents(projectFilesystem.resolve("subdir/new_defs.bzl"));

    parser = createParser(eventCollector);

    String rule1Identifier = "//subdir:defs.bzl:some_rule";
    String rule2Identifier = "//subdir:defs.bzl:some_other_rule";

    KnownUserDefinedRuleTypes knownUserDefinedRuleTypes =
        knownRuleTypesProvider.getUserDefinedRuleTypes(cell.getRootCell());

    assertNull(knownUserDefinedRuleTypes.getRule(rule1Identifier));
    assertNull(knownUserDefinedRuleTypes.getRule(rule2Identifier));

    ForwardRelPath buildFile = ForwardRelPath.of("subdir/BUCK");

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
        replacement, projectFilesystem.resolve("subdir/defs.bzl").getPath());
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
    parser = createParser(eventCollector);

    String rule1Identifier = "//subdir:defs.bzl:some_rule";
    String rule2Identifier = "//subdir:defs.bzl:some_other_rule";

    KnownUserDefinedRuleTypes knownUserDefinedRuleTypes =
        knownRuleTypesProvider.getUserDefinedRuleTypes(cell.getRootCell());
    assertNull(knownUserDefinedRuleTypes.getRule(rule1Identifier));
    assertNull(knownUserDefinedRuleTypes.getRule(rule2Identifier));

    BuildFileManifest rules = parser.getManifest(ForwardRelPath.of("subdir/BUCK"));
    assertEquals(2, rules.getTargets().size());

    SkylarkUserDefinedRule rule1 = knownUserDefinedRuleTypes.getRule(rule1Identifier);
    SkylarkUserDefinedRule rule2 = knownUserDefinedRuleTypes.getRule(rule2Identifier);

    assertNotNull(rule1);
    assertNotNull(rule2);
    assertEquals(rule1Identifier, rule1.getName());
    assertEquals(rule2Identifier, rule2.getName());
    assertEquals(Integer.class, rule1.getParamsInfo().getByStarlarkName("attr1").getResultClass());
    assertEquals(Integer.class, rule2.getParamsInfo().getByStarlarkName("attr2").getResultClass());
  }

  @Test
  public void builtInProvidersAreExportedWhenEnabled() throws IOException, InterruptedException {
    setupWorkspace("builtin_providers_exported");
    AbsPath buildFile = projectFilesystem.resolve("subdir/BUCK");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    assertEquals("target1", getSingleRule(buildFile).getBySnakeCase("name"));
    Iterables.find(
        collector,
        e -> e.getKind() == EventKind.DEBUG && e.getMessage().contains("in bzl: DefaultInfo("));
  }

  @Test
  public void providerFunctionFailsIfInvalidFieldNameProvided()
      throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    parser.getManifest(ForwardRelPath.of("invalid_field/BUCK"));
  }

  @Test
  public void providerFunctionFailsIfInvalidTypeProvidedForFields()
      throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    parser.getManifest(ForwardRelPath.of("invalid_type/BUCK"));
  }

  @Test
  public void providerFunctionFailsIfInvalidListType() throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    parser.getManifest(ForwardRelPath.of("invalid_list_type/BUCK"));
  }

  @Test
  public void providerFunctionFailsIfInvalidDictType() throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    parser.getManifest(ForwardRelPath.of("invalid_dict_type/BUCK"));
  }

  @Test
  public void providerInfoFromProviderFunctionHasCorrectFields()
      throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    parser.getManifest(ForwardRelPath.of("valid_provider/BUCK"));
  }

  @Test
  public void providerCannotBeUsedIfNotAssigned() throws IOException, InterruptedException {
    setupWorkspace("user_defined_providers");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    // TODO(T48080142): When we wrap up all of these validation errors into something human
    // friendly, this will become a build file error
    thrown.expect(Exception.class);
    thrown.expectMessage("//not_assigned:defs.bzl referenced from //not_assigned:BUCK");
    parser.getManifest(ForwardRelPath.of("not_assigned/BUCK"));
  }

  @Test
  public void providerCanBeInstantiatedRightAfterDefined() throws Exception {
    setupWorkspace("user_defined_providers");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    parser.getManifest(ForwardRelPath.of("valid_provider_used_after_defined/BUCK"));
  }

  @Test
  public void testRulesMustEndInTest() throws IOException, InterruptedException {
    setupWorkspace("basic_rule");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Only rules with `test = True` may end with `_test`");
    parser.getManifest(ForwardRelPath.of("non_test_rule_name/BUCK"));
  }

  @Test
  public void nonTestRulesMustNotEndInTest() throws IOException, InterruptedException {

    setupWorkspace("basic_rule");

    EventCollector collector = new EventCollector(EnumSet.allOf(EventKind.class));
    parser = createParser(collector);

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Rules with `test = True` must end with `_test`");
    parser.getManifest(ForwardRelPath.of("test_rule_name/BUCK"));
  }
}

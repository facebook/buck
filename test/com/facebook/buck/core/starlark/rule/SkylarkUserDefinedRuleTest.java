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

package com.facebook.buck.core.starlark.rule;

import static com.facebook.buck.skylark.function.SkylarkRuleFunctions.IMPLICIT_ATTRIBUTES;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.starlark.coercer.SkylarkParamInfo;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.AttributeHolder;
import com.facebook.buck.core.starlark.rule.attr.impl.IntAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.StringAttribute;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.packages.BazelLibrary;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Identifier;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkType;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.Set;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SkylarkUserDefinedRuleTest {

  private static final Set<String> HIDDEN_IMPLICIT_ATTRIBUTES = ImmutableSet.of();
  @Rule public ExpectedException expectedException = ExpectedException.none();

  Location location = Location.BUILTIN;

  private static final ImmutableMap<String, Attribute<?>> TEST_IMPLICIT_ATTRIBUTES =
      ImmutableMap.of("name", IMPLICIT_ATTRIBUTES.get("name"));

  public static class SimpleFunction extends BaseFunction {

    public SimpleFunction(
        String name, FunctionSignature.WithValues<Object, SkylarkType> signature) {
      super(name, signature);
    }

    static SimpleFunction of(int numArgs) {
      String[] names =
          IntStream.range(0, numArgs)
              .mapToObj(i -> String.format("arg%d", i))
              .toArray(String[]::new);

      FunctionSignature.WithValues<Object, SkylarkType> signature =
          FunctionSignature.WithValues.create(
              FunctionSignature.of(numArgs, 0, 0, false, false, names), null, null);
      return new SimpleFunction("a_func", signature);
    }

    @Override
    public Object call(Object[] args, @Nullable FuncallExpression ast, Environment env)
        throws EvalException, InterruptedException {
      throw new UnsupportedOperationException();
    }
  }

  private Environment newEnvironment(Mutability mutability) throws LabelSyntaxException {
    PrintingEventHandler eventHandler = new PrintingEventHandler(EventKind.ALL_EVENTS);
    ParseContext parseContext =
        new ParseContext(
            PackageContext.of(
                (include, exclude, excludeDirectories) -> {
                  throw new UnsupportedOperationException();
                },
                ImmutableMap.of(),
                PackageIdentifier.create(
                    "@repo", PathFragment.create("some_package").getChild("subdir")),
                eventHandler,
                ImmutableMap.of()));

    Environment env =
        Environment.builder(mutability)
            .setGlobals(BazelLibrary.GLOBALS)
            .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
            .build();
    parseContext.setup(env);
    return env;
  }

  /**
   * Create a dummy AST so that we can pass a non-null ast into call(). This is only really used in
   * logging during an error case that is not encountered in the wild, so just go with the easiest
   * ast to construct
   */
  private FuncallExpression getJunkAst() {
    FuncallExpression ast = new FuncallExpression(Identifier.of("junk"), ImmutableList.of());
    ast.setLocation(Location.BUILTIN);
    return ast;
  }

  @Test
  public void getsCorrectName() throws LabelSyntaxException, EvalException {
    ImmutableMap<String, AttributeHolder> params =
        ImmutableMap.of(
            "arg1", StringAttribute.of("some string", "", false, ImmutableList.of()),
            "_arg2", StringAttribute.of("some string", "", true, ImmutableList.of()));

    SkylarkUserDefinedRule rule =
        SkylarkUserDefinedRule.of(
            location,
            SimpleFunction.of(1),
            TEST_IMPLICIT_ATTRIBUTES,
            HIDDEN_IMPLICIT_ATTRIBUTES,
            params,
            false,
            false);
    rule.export(Label.parseAbsolute("@foo//bar:extension.bzl", ImmutableMap.of()), "baz_rule");

    assertEquals("@foo//bar:extension.bzl:baz_rule", rule.getName());
    assertEquals(
        Label.parseAbsolute("@foo//bar:extension.bzl", ImmutableMap.of()), rule.getLabel());
    assertEquals("baz_rule", rule.getExportedName());
  }

  @Test
  public void filtersOutArgumentsStartingWithUnderscore()
      throws EvalException, LabelSyntaxException {
    ImmutableMap<String, AttributeHolder> params =
        ImmutableMap.of(
            "arg1", StringAttribute.of("some string", "", false, ImmutableList.of()),
            "_arg2", StringAttribute.of("some string", "", true, ImmutableList.of()));

    ImmutableList<String> expectedOrder = ImmutableList.of("name", "arg1");
    ImmutableList<String> expectedRawArgs = ImmutableList.of("name", "arg1", "_arg2");

    SkylarkUserDefinedRule rule =
        SkylarkUserDefinedRule.of(
            location,
            SimpleFunction.of(1),
            TEST_IMPLICIT_ATTRIBUTES,
            HIDDEN_IMPLICIT_ATTRIBUTES,
            params,
            false,
            false);
    rule.export(Label.parseAbsolute("@foo//bar:extension.bzl", ImmutableMap.of()), "baz_rule");

    assertEquals(expectedOrder, rule.getSignature().getSignature().getNames());
    assertEquals(expectedRawArgs, ImmutableList.copyOf(rule.getAttrs().keySet()));
  }

  @Test
  public void movesNameToFirstArgAndPutsMandatoryArgsAheadOfOptionalOnesAndSorts()
      throws EvalException, LabelSyntaxException {
    ImmutableMap<String, AttributeHolder> params =
        ImmutableMap.<String, AttributeHolder>builder()
            .put("arg1", StringAttribute.of("some string", "", false, ImmutableList.of()))
            .put("arg9", StringAttribute.of("some string", "", true, ImmutableList.of()))
            .put("arg2", StringAttribute.of("some string", "", true, ImmutableList.of()))
            .put("arg3", IntAttribute.of(5, "", false, ImmutableList.of()))
            .put("arg8", IntAttribute.of(5, "", false, ImmutableList.of()))
            .put("arg4", IntAttribute.of(5, "", true, ImmutableList.of()))
            .build();

    ImmutableList<String> expectedOrder =
        ImmutableList.of("name", "arg2", "arg4", "arg9", "arg1", "arg3", "arg8");

    SkylarkUserDefinedRule rule =
        SkylarkUserDefinedRule.of(
            location,
            SimpleFunction.of(1),
            TEST_IMPLICIT_ATTRIBUTES,
            HIDDEN_IMPLICIT_ATTRIBUTES,
            params,
            false,
            false);
    rule.export(Label.parseAbsolute("@foo//bar:extension.bzl", ImmutableMap.of()), "baz_rule");

    assertEquals(expectedOrder, rule.getSignature().getSignature().getNames());
  }

  @Test
  public void raisesErrorIfImplementationTakesZeroArgs() throws EvalException {
    ImmutableMap<String, AttributeHolder> params = ImmutableMap.of();

    expectedException.expect(EvalException.class);
    expectedException.expectMessage(
        "Implementation function 'a_func' must accept a single 'ctx' argument. Accepts 0 arguments");

    SkylarkUserDefinedRule.of(
        location,
        SimpleFunction.of(0),
        TEST_IMPLICIT_ATTRIBUTES,
        HIDDEN_IMPLICIT_ATTRIBUTES,
        params,
        false,
        false);
  }

  @Test
  public void raisesErrorIfImplementationTakesMoreThanOneArg() throws EvalException {
    ImmutableMap<String, AttributeHolder> params = ImmutableMap.of();

    expectedException.expect(EvalException.class);
    expectedException.expectMessage(
        "Implementation function 'a_func' must accept a single 'ctx' argument. Accepts 2 arguments");

    SkylarkUserDefinedRule.of(
        location,
        SimpleFunction.of(2),
        TEST_IMPLICIT_ATTRIBUTES,
        HIDDEN_IMPLICIT_ATTRIBUTES,
        params,
        false,
        false);
  }

  @Test
  public void raisesErrorIfArgumentDuplicatesBuiltInName() throws EvalException {
    ImmutableMap<String, AttributeHolder> params =
        ImmutableMap.of("name", StringAttribute.of("some string", "", false, ImmutableList.of()));

    expectedException.expect(EvalException.class);
    expectedException.expectMessage(
        "Provided attr 'name' shadows implicit attribute. Please remove it.");

    SkylarkUserDefinedRule.of(
        location,
        SimpleFunction.of(1),
        TEST_IMPLICIT_ATTRIBUTES,
        HIDDEN_IMPLICIT_ATTRIBUTES,
        params,
        false,
        false);
  }

  @Test
  public void acceptsAutomaticallyAddedParameters()
      throws EvalException, LabelSyntaxException, InterruptedException {
    // TODO: Add visibility when that's added to implicit params
    ImmutableMap<String, AttributeHolder> params = ImmutableMap.of();
    ImmutableMap<String, Object> expected =
        ImmutableMap.of(
            "buck.base_path", "some_package/subdir",
            "buck.type", "@foo//bar:extension.bzl:baz_rule",
            "name", "some_rule_name");

    SkylarkUserDefinedRule rule =
        SkylarkUserDefinedRule.of(
            location,
            SimpleFunction.of(1),
            TEST_IMPLICIT_ATTRIBUTES,
            HIDDEN_IMPLICIT_ATTRIBUTES,
            params,
            false,
            false);
    rule.export(Label.parseAbsolute("@foo//bar:extension.bzl", ImmutableMap.of()), "baz_rule");

    try (Mutability mutability = Mutability.create("argtest")) {

      Environment env = newEnvironment(mutability);

      Object res =
          rule.call(
              ImmutableList.of(), ImmutableMap.of("name", "some_rule_name"), getJunkAst(), env);

      ImmutableMap<String, ImmutableMap<String, Object>> rules =
          ParseContext.getParseContext(env, null).getRecordedRules();

      assertEquals(Runtime.NONE, res);
      assertEquals(1, rules.size());
      assertEquals(expected, rules.get("some_rule_name"));
    }
  }

  @Test
  public void usesDefaultValuesIfMissingParameter()
      throws LabelSyntaxException, InterruptedException, EvalException {
    ImmutableMap<String, AttributeHolder> params =
        ImmutableMap.of(
            "arg1", StringAttribute.of("some string", "", false, ImmutableList.of()),
            "arg2", StringAttribute.of("some string", "", true, ImmutableList.of()),
            "arg3", IntAttribute.of(5, "", false, ImmutableList.of()),
            "arg4", IntAttribute.of(5, "", true, ImmutableList.of()));
    ImmutableMap<String, Object> expected =
        ImmutableMap.<String, Object>builder()
            .put("buck.base_path", "some_package/subdir")
            .put("buck.type", "@foo//bar:extension.bzl:baz_rule")
            .put("name", "some_rule_name")
            .put("arg1", "some string")
            .put("arg2", "arg2_val")
            .put("arg3", 5)
            .put("arg4", 2)
            .build();

    SkylarkUserDefinedRule rule =
        SkylarkUserDefinedRule.of(
            location,
            SimpleFunction.of(1),
            TEST_IMPLICIT_ATTRIBUTES,
            HIDDEN_IMPLICIT_ATTRIBUTES,
            params,
            false,
            false);
    rule.export(Label.parseAbsolute("@foo//bar:extension.bzl", ImmutableMap.of()), "baz_rule");

    try (Mutability mutability = Mutability.create("argtest")) {

      Environment env = newEnvironment(mutability);

      Object res =
          rule.call(
              ImmutableList.of(),
              ImmutableMap.of("name", "some_rule_name", "arg2", "arg2_val", "arg4", 2),
              getJunkAst(),
              env);

      ImmutableMap<String, ImmutableMap<String, Object>> rules =
          ParseContext.getParseContext(env, null).getRecordedRules();

      assertEquals(Runtime.NONE, res);
      assertEquals(1, rules.size());
      assertEquals(expected, rules.get("some_rule_name"));
    }
  }

  @Test
  public void raisesErrorIfMandatoryParameterMissing()
      throws LabelSyntaxException, InterruptedException, EvalException {
    ImmutableMap<String, AttributeHolder> params =
        ImmutableMap.of(
            "arg1", StringAttribute.of("some string", "", false, ImmutableList.of()),
            "arg2", StringAttribute.of("some string", "", true, ImmutableList.of()),
            "arg3", IntAttribute.of(5, "", false, ImmutableList.of()),
            "arg4", IntAttribute.of(5, "", true, ImmutableList.of()));

    SkylarkUserDefinedRule rule =
        SkylarkUserDefinedRule.of(
            location,
            SimpleFunction.of(1),
            TEST_IMPLICIT_ATTRIBUTES,
            HIDDEN_IMPLICIT_ATTRIBUTES,
            params,
            false,
            false);
    rule.export(Label.parseAbsolute("@foo//bar:extension.bzl", ImmutableMap.of()), "baz_rule");

    try (Mutability mutability = Mutability.create("argtest")) {

      Environment env = newEnvironment(mutability);

      expectedException.expect(EvalException.class);
      expectedException.expectMessage(
          "missing mandatory named-only argument 'arg4' while calling @foo//bar:extension.bzl:baz_rule(name, arg2, arg4, arg1 = \"some string\", arg3 = 5)");
      rule.call(
          ImmutableList.of(),
          ImmutableMap.of("name", "some_rule_name", "arg2", "arg2_val"),
          getJunkAst(),
          env);
    }
  }

  @Test
  public void raisesErrorIfInvalidCharsInArgumentNameAreProvided() throws EvalException {
    ImmutableMap<String, AttributeHolder> params =
        ImmutableMap.of(
            "invalid-name", StringAttribute.of("some string", "", false, ImmutableList.of()));

    expectedException.expect(EvalException.class);
    expectedException.expectMessage("Attribute name 'invalid-name' is not a valid identifier");
    SkylarkUserDefinedRule.of(
        location,
        SimpleFunction.of(1),
        TEST_IMPLICIT_ATTRIBUTES,
        HIDDEN_IMPLICIT_ATTRIBUTES,
        params,
        false,
        false);
  }

  @Test
  public void raisesErrorIfEmptyArgumentNameIsProvided() throws EvalException {
    ImmutableMap<String, AttributeHolder> params =
        ImmutableMap.of("", StringAttribute.of("some string", "", false, ImmutableList.of()));

    expectedException.expect(EvalException.class);
    expectedException.expectMessage("Attribute name '' is not a valid identifier");
    SkylarkUserDefinedRule.of(
        location,
        SimpleFunction.of(1),
        TEST_IMPLICIT_ATTRIBUTES,
        HIDDEN_IMPLICIT_ATTRIBUTES,
        params,
        false,
        false);
  }

  @Test
  public void createsCorrectCallable()
      throws EvalException, IOException, InterruptedException, LabelSyntaxException {
    ImmutableMap<String, AttributeHolder> params =
        ImmutableMap.of(
            "arg1", StringAttribute.of("some string", "", false, ImmutableList.of()),
            "arg2", StringAttribute.of("some string", "", true, ImmutableList.of()),
            "arg3", IntAttribute.of(5, "", false, ImmutableList.of()),
            "arg4", IntAttribute.of(5, "", true, ImmutableList.of()));
    ImmutableMap<String, Object> expected =
        ImmutableMap.<String, Object>builder()
            .put("buck.base_path", "some_package/subdir")
            .put("buck.type", "@foo//bar:extension.bzl:baz_rule")
            .put("name", "some_rule_name")
            .put("arg1", "arg1_val")
            .put("arg2", "arg2_val")
            .put("arg3", 1)
            .put("arg4", 2)
            .build();

    SkylarkUserDefinedRule rule =
        SkylarkUserDefinedRule.of(
            location,
            SimpleFunction.of(1),
            TEST_IMPLICIT_ATTRIBUTES,
            HIDDEN_IMPLICIT_ATTRIBUTES,
            params,
            false,
            false);
    rule.export(Label.parseAbsolute("@foo//bar:extension.bzl", ImmutableMap.of()), "baz_rule");

    try (Mutability mutability = Mutability.create("argtest")) {

      Environment env = newEnvironment(mutability);

      Object res =
          rule.call(
              ImmutableList.of(),
              ImmutableMap.of(
                  "name",
                  "some_rule_name",
                  "arg1",
                  "arg1_val",
                  "arg2",
                  "arg2_val",
                  "arg3",
                  1,
                  "arg4",
                  2),
              getJunkAst(),
              env);

      ImmutableMap<String, ImmutableMap<String, Object>> rules =
          ParseContext.getParseContext(env, null).getRecordedRules();

      assertEquals(Runtime.NONE, res);
      assertEquals(1, rules.size());
      assertEquals(expected, rules.get("some_rule_name"));
    }
  }

  @Test
  public void returnsParamInfos() throws EvalException {

    ImmutableMap<String, AttributeHolder> params =
        ImmutableMap.of("arg1", StringAttribute.of("some string", "", false, ImmutableList.of()));
    SkylarkUserDefinedRule rule =
        SkylarkUserDefinedRule.of(
            Location.BUILTIN,
            SimpleFunction.of(1),
            TEST_IMPLICIT_ATTRIBUTES,
            HIDDEN_IMPLICIT_ATTRIBUTES,
            params,
            false,
            false);

    ImmutableMap<String, ParamInfo<?>> paramInfos = rule.getAllParamInfo();

    assertEquals(ImmutableSet.of("name", "arg1"), paramInfos.keySet());

    SkylarkParamInfo name = (SkylarkParamInfo) paramInfos.get("name");
    SkylarkParamInfo arg1 = (SkylarkParamInfo) paramInfos.get("arg1");

    assertEquals("name", name.getName());
    assertEquals(
        StringAttribute.of("", "The name of the target", true, ImmutableList.of()), name.getAttr());

    assertEquals("arg1", arg1.getName());
    assertEquals(params.get("arg1").getAttribute(), arg1.getAttr());
  }
}

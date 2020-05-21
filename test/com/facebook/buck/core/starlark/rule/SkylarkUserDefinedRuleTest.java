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
import com.facebook.buck.core.starlark.testutil.TestStarlarkParser;
import com.facebook.buck.rules.coercer.ParamsInfo;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.facebook.buck.skylark.parser.context.RecordedRule;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.CallExpression;
import com.google.devtools.build.lib.syntax.Dict;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Module;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.Tuple;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Set;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SkylarkUserDefinedRuleTest {

  private static final Set<ParamName> HIDDEN_IMPLICIT_ATTRIBUTES = ImmutableSet.of();
  @Rule public ExpectedException expectedException = ExpectedException.none();

  final Location location = Location.BUILTIN;

  private static final ImmutableMap<ParamName, Attribute<?>> TEST_IMPLICIT_ATTRIBUTES =
      ImmutableMap.of(
          ParamName.bySnakeCase("name"), IMPLICIT_ATTRIBUTES.get(ParamName.bySnakeCase("name")));

  public static class SimpleFunction extends BaseFunction {

    private final String name;
    private final FunctionSignature signature;
    private final Tuple<Object> defaultValues;

    public SimpleFunction(
        String name, FunctionSignature signature, ImmutableList<Object> defaultValues) {
      this.name = name;
      this.signature = signature;
      this.defaultValues = Tuple.copyOf(defaultValues);
    }

    public SimpleFunction(String name, FunctionSignature signature) {
      this.name = name;
      this.signature = signature;
      this.defaultValues = Tuple.of();
    }

    @Override
    public FunctionSignature getSignature() {
      return signature;
    }

    @Nullable
    @Override
    public Tuple<Object> getDefaultValues() {
      return defaultValues;
    }

    @Override
    public String getName() {
      return name;
    }

    static SimpleFunction of(int numArgs) {
      ImmutableList<String> names =
          IntStream.range(0, numArgs)
              .mapToObj(i -> String.format("arg%d", i))
              .collect(ImmutableList.toImmutableList());

      FunctionSignature signature = FunctionSignature.create(numArgs, 0, 0, 0, false, false, names);
      return new SimpleFunction("a_func", signature);
    }

    @Override
    public Object call(
        StarlarkThread thread, Location loc, Tuple<Object> args, Dict<String, Object> kwargs)
        throws EvalException, InterruptedException {
      throw new UnsupportedOperationException();
    }
  }

  private StarlarkThread newEnvironment(Mutability mutability) throws LabelSyntaxException {
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

    StarlarkThread env =
        StarlarkThread.builder(mutability)
            .setGlobals(Module.createForBuiltins(Starlark.UNIVERSE))
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
  private CallExpression getJunkAst() {
    return TestStarlarkParser.parseFuncall("junk()");
  }

  @Test
  public void getsCorrectName() throws LabelSyntaxException, EvalException {
    ImmutableMap<ParamName, AttributeHolder> params =
        ImmutableMap.of(
            ParamName.bySnakeCase("arg1"),
                StringAttribute.of("some string", "", false, ImmutableList.of()),
            ParamName.bySnakeCase("_arg2"),
                StringAttribute.of("some string", "", true, ImmutableList.of()));

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
    ImmutableMap<ParamName, AttributeHolder> params =
        ImmutableMap.of(
            ParamName.bySnakeCase("arg1"),
                StringAttribute.of("some string", "", false, ImmutableList.of()),
            ParamName.bySnakeCase("_arg2"),
                StringAttribute.of("some string", "", true, ImmutableList.of()));

    ImmutableList<String> expectedOrder = ImmutableList.of("name", "arg1");
    ImmutableList<ParamName> expectedRawArgs =
        ImmutableList.of(
            ParamName.bySnakeCase("name"),
            ParamName.bySnakeCase("arg1"),
            ParamName.bySnakeCase("_arg2"));

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

    assertEquals(expectedOrder, rule.getSignature().getParameterNames());
    assertEquals(expectedRawArgs, ImmutableList.copyOf(rule.getAttrs().keySet()));
  }

  @Test
  public void movesNameToFirstArgAndPutsMandatoryArgsAheadOfOptionalOnesAndSorts()
      throws EvalException, LabelSyntaxException {
    ImmutableMap<ParamName, AttributeHolder> params =
        ImmutableMap.<ParamName, AttributeHolder>builder()
            .put(
                ParamName.bySnakeCase("arg1"),
                StringAttribute.of("some string", "", false, ImmutableList.of()))
            .put(
                ParamName.bySnakeCase("arg9"),
                StringAttribute.of("some string", "", true, ImmutableList.of()))
            .put(
                ParamName.bySnakeCase("arg2"),
                StringAttribute.of("some string", "", true, ImmutableList.of()))
            .put(ParamName.bySnakeCase("arg3"), IntAttribute.of(5, "", false, ImmutableList.of()))
            .put(ParamName.bySnakeCase("arg8"), IntAttribute.of(5, "", false, ImmutableList.of()))
            .put(ParamName.bySnakeCase("arg4"), IntAttribute.of(5, "", true, ImmutableList.of()))
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

    assertEquals(expectedOrder, rule.getSignature().getParameterNames());
  }

  @Test
  public void raisesErrorIfImplementationTakesZeroArgs() throws EvalException {
    ImmutableMap<ParamName, AttributeHolder> params = ImmutableMap.of();

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
    ImmutableMap<ParamName, AttributeHolder> params = ImmutableMap.of();

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
    ImmutableMap<ParamName, AttributeHolder> params =
        ImmutableMap.of(
            ParamName.bySnakeCase("name"),
            StringAttribute.of("some string", "", false, ImmutableList.of()));

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
    ImmutableMap<ParamName, AttributeHolder> params = ImmutableMap.of();
    ImmutableMap<ParamName, Object> expected =
        ImmutableMap.of(ParamName.bySnakeCase("name"), "some_rule_name");

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

      StarlarkThread env = newEnvironment(mutability);

      Object res =
          Starlark.call(
              env,
              rule,
              getJunkAst().getLocation(),
              ImmutableList.of(),
              ImmutableMap.of("name", "some_rule_name"));

      TwoArraysImmutableHashMap<String, RecordedRule> rules =
          ParseContext.getParseContext(env, Location.BUILTIN, "some_rule_name").getRecordedRules();

      assertEquals(Starlark.NONE, res);
      assertEquals(1, rules.size());
      assertEquals("@foo//bar:extension.bzl:baz_rule", rules.get("some_rule_name").getBuckType());
      assertEquals("some_package/subdir", rules.get("some_rule_name").getBasePath().toString());
      assertEquals(expected, rules.get("some_rule_name").getRawRule());
    }
  }

  @Test
  public void usesDefaultValuesIfMissingParameter()
      throws LabelSyntaxException, InterruptedException, EvalException {
    ImmutableMap<ParamName, AttributeHolder> params =
        ImmutableMap.of(
            ParamName.bySnakeCase("arg1"),
                StringAttribute.of("some string", "", false, ImmutableList.of()),
            ParamName.bySnakeCase("arg2"),
                StringAttribute.of("some string", "", true, ImmutableList.of()),
            ParamName.bySnakeCase("arg3"), IntAttribute.of(5, "", false, ImmutableList.of()),
            ParamName.bySnakeCase("arg4"), IntAttribute.of(5, "", true, ImmutableList.of()));
    ImmutableMap<ParamName, Object> expected =
        ImmutableMap.<ParamName, Object>builder()
            .put(ParamName.bySnakeCase("name"), "some_rule_name")
            .put(ParamName.bySnakeCase("arg1"), "some string")
            .put(ParamName.bySnakeCase("arg2"), "arg2_val")
            .put(ParamName.bySnakeCase("arg3"), 5)
            .put(ParamName.bySnakeCase("arg4"), 2)
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

      StarlarkThread env = newEnvironment(mutability);

      Object res =
          Starlark.call(
              env,
              rule,
              getJunkAst().getLocation(),
              ImmutableList.of(),
              ImmutableMap.of("name", "some_rule_name", "arg2", "arg2_val", "arg4", 2));

      TwoArraysImmutableHashMap<String, RecordedRule> rules =
          ParseContext.getParseContext(env, Location.BUILTIN, "some_rule_name").getRecordedRules();

      assertEquals(Starlark.NONE, res);
      assertEquals(1, rules.size());
      assertEquals("@foo//bar:extension.bzl:baz_rule", rules.get("some_rule_name").getBuckType());
      assertEquals("some_package/subdir", rules.get("some_rule_name").getBasePath().toString());
      assertEquals(expected, rules.get("some_rule_name").getRawRule());
    }
  }

  @Test
  public void raisesErrorIfMandatoryParameterMissing()
      throws LabelSyntaxException, InterruptedException, EvalException {
    ImmutableMap<ParamName, AttributeHolder> params =
        ImmutableMap.of(
            ParamName.bySnakeCase("arg1"),
                StringAttribute.of("some string", "", false, ImmutableList.of()),
            ParamName.bySnakeCase("arg2"),
                StringAttribute.of("some string", "", true, ImmutableList.of()),
            ParamName.bySnakeCase("arg3"), IntAttribute.of(5, "", false, ImmutableList.of()),
            ParamName.bySnakeCase("arg4"), IntAttribute.of(5, "", true, ImmutableList.of()));

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

      StarlarkThread env = newEnvironment(mutability);

      expectedException.expect(EvalException.class);
      expectedException.expectMessage(
          "missing mandatory named-only argument 'arg4' while calling @foo//bar:extension.bzl:baz_rule(*, name, arg2, arg4, arg1 = \"some string\", arg3 = 5)");
      Starlark.call(
          env,
          rule,
          getJunkAst().getLocation(),
          ImmutableList.of(),
          ImmutableMap.of("name", "some_rule_name", "arg2", "arg2_val"));
    }
  }

  @Test
  public void createsCorrectCallable()
      throws EvalException, InterruptedException, LabelSyntaxException {
    ImmutableMap<ParamName, AttributeHolder> params =
        ImmutableMap.of(
            ParamName.bySnakeCase("arg1"),
                StringAttribute.of("some string", "", false, ImmutableList.of()),
            ParamName.bySnakeCase("arg2"),
                StringAttribute.of("some string", "", true, ImmutableList.of()),
            ParamName.bySnakeCase("arg3"), IntAttribute.of(5, "", false, ImmutableList.of()),
            ParamName.bySnakeCase("arg4"), IntAttribute.of(5, "", true, ImmutableList.of()));
    ImmutableMap<ParamName, Object> expected =
        ImmutableMap.<ParamName, Object>builder()
            .put(ParamName.bySnakeCase("name"), "some_rule_name")
            .put(ParamName.bySnakeCase("arg1"), "arg1_val")
            .put(ParamName.bySnakeCase("arg2"), "arg2_val")
            .put(ParamName.bySnakeCase("arg3"), 1)
            .put(ParamName.bySnakeCase("arg4"), 2)
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

      StarlarkThread env = newEnvironment(mutability);

      Object res =
          Starlark.call(
              env,
              rule,
              getJunkAst().getLocation(),
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
                  2));

      TwoArraysImmutableHashMap<String, RecordedRule> rules =
          ParseContext.getParseContext(env, Location.BUILTIN, "some_rule_name").getRecordedRules();

      assertEquals(Starlark.NONE, res);
      assertEquals(1, rules.size());
      assertEquals("some_package/subdir", rules.get("some_rule_name").getBasePath().toString());
      assertEquals("@foo//bar:extension.bzl:baz_rule", rules.get("some_rule_name").getBuckType());
      assertEquals(expected, rules.get("some_rule_name").getRawRule());
    }
  }

  @Test
  public void returnsParamInfos() throws EvalException {

    ImmutableMap<ParamName, AttributeHolder> params =
        ImmutableMap.of(
            ParamName.bySnakeCase("arg1"),
            StringAttribute.of("some string", "", false, ImmutableList.of()));
    SkylarkUserDefinedRule rule =
        SkylarkUserDefinedRule.of(
            Location.BUILTIN,
            SimpleFunction.of(1),
            TEST_IMPLICIT_ATTRIBUTES,
            HIDDEN_IMPLICIT_ATTRIBUTES,
            params,
            false,
            false);

    ParamsInfo paramsInfo = rule.getParamsInfo();

    assertEquals(
        ImmutableSet.of("name", "arg1"), paramsInfo.getParamInfosByStarlarkName().keySet());

    SkylarkParamInfo name = (SkylarkParamInfo) paramsInfo.getByStarlarkName("name");
    SkylarkParamInfo arg1 = (SkylarkParamInfo) paramsInfo.getByStarlarkName("arg1");

    assertEquals("name", name.getName().getSnakeCase());
    assertEquals(
        StringAttribute.of("", "The name of the target", true, ImmutableList.of()), name.getAttr());

    assertEquals("arg1", arg1.getName().getSnakeCase());
    assertEquals(params.get(ParamName.bySnakeCase("arg1")).getAttribute(), arg1.getAttr());
  }
}

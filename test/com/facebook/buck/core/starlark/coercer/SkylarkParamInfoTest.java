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

package com.facebook.buck.core.starlark.coercer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.coercer.ListTypeCoercer;
import com.facebook.buck.rules.coercer.MapTypeCoercer;
import com.facebook.buck.rules.coercer.NumberTypeCoercer;
import com.facebook.buck.rules.coercer.OptionalTypeCoercer;
import com.facebook.buck.rules.coercer.ParamInfoException;
import com.facebook.buck.rules.coercer.StringTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.skylark.function.FakeSkylarkUserDefinedRuleFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.EvalException;
import java.lang.reflect.Type;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SkylarkParamInfoTest {
  @Rule public ExpectedException expected = ExpectedException.none();
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @BuckStyleValue
  abstract static class TestIntAttribute extends Attribute<Integer> {

    @Override
    public abstract Integer getPreCoercionDefaultValue();

    @Override
    public abstract String getDoc();

    @Override
    public abstract boolean getMandatory();

    @Override
    public TypeCoercer<?, Integer> getTypeCoercer() {
      return new NumberTypeCoercer<>(Integer.class);
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("<attr.test_int>");
    }
  }

  @BuckStyleValue
  abstract static class TestOptionalIntAttribute extends Attribute<Optional<Integer>> {

    @Override
    public abstract Optional<Integer> getPreCoercionDefaultValue();

    @Override
    public abstract String getDoc();

    @Override
    public abstract boolean getMandatory();

    @Override
    public TypeCoercer<?, Optional<Integer>> getTypeCoercer() {
      return new OptionalTypeCoercer<>(new NumberTypeCoercer<>(Integer.class));
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("<attr.test_optional_int>");
    }
  }

  @BuckStyleValue
  abstract static class TestListStringAttribute extends Attribute<ImmutableList<String>> {
    @Override
    public abstract ImmutableList<String> getPreCoercionDefaultValue();

    @Override
    public abstract String getDoc();

    @Override
    public abstract boolean getMandatory();

    @Override
    public TypeCoercer<?, ImmutableList<String>> getTypeCoercer() {
      return new ListTypeCoercer<>(new StringTypeCoercer());
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("<attr.test_list_string>");
    }
  }

  @BuckStyleValue
  abstract static class TestMapStringAttribute extends Attribute<ImmutableMap<String, Integer>> {
    @Override
    public abstract ImmutableMap<String, Integer> getPreCoercionDefaultValue();

    @Override
    public abstract String getDoc();

    @Override
    public abstract boolean getMandatory();

    @Override
    public TypeCoercer<?, ImmutableMap<String, Integer>> getTypeCoercer() {
      return new MapTypeCoercer<>(new StringTypeCoercer(), new NumberTypeCoercer<>(Integer.class));
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("<attr.test_map_string>");
    }
  }

  @Test
  public void attributesOfOptionalsAreOptional() {
    SkylarkParamInfo<?> nonOptionalInfo =
        new SkylarkParamInfo<>("foo", ImmutableTestIntAttribute.of(1, "", false));
    SkylarkParamInfo<?> optionalInfo =
        new SkylarkParamInfo<>(
            "foo", ImmutableTestOptionalIntAttribute.of(Optional.empty(), "", false));

    assertFalse(nonOptionalInfo.isOptional());
    assertTrue(optionalInfo.isOptional());
  }

  @Test
  public void doesNotReturnAHint() {
    SkylarkParamInfo<?> info =
        new SkylarkParamInfo<>("foo", ImmutableTestIntAttribute.of(1, "", false));
    assertNull(info.getHint());
  }

  @Test
  public void errorsOnWrongDTOTypeInGet() {
    SkylarkParamInfo<?> info =
        new SkylarkParamInfo<>("foo", ImmutableTestIntAttribute.of(1, "", false));

    expected.expect(IllegalArgumentException.class);

    info.get("invalid");
  }

  @Test
  public void errorsOnWrongDTOTypeInSet() {
    SkylarkParamInfo<?> info =
        new SkylarkParamInfo<>("foo", ImmutableTestIntAttribute.of(1, "", false));

    expected.expect(IllegalArgumentException.class);

    info.setCoercedValue("", 5);
  }

  @Test
  public void returnsEmptyGenericsOnNonGenericCoercer() {
    SkylarkParamInfo<?> info =
        new SkylarkParamInfo<>("foo", ImmutableTestIntAttribute.of(1, "", false));
    assertEquals(Integer.class, info.getTypeCoercer().getOutputType().getType());
  }

  @Test
  public void returnsCorrectGenericsOnGenericCoercer() {
    SkylarkParamInfo<?> listInfo =
        new SkylarkParamInfo<>(
            "foo", ImmutableTestListStringAttribute.of(ImmutableList.of(), "", false));

    SkylarkParamInfo<?> mapInfo =
        new SkylarkParamInfo<>(
            "foo", ImmutableTestMapStringAttribute.of(ImmutableMap.of(), "", false));

    Type listParamTypes = listInfo.getTypeCoercer().getOutputType().getType();
    Type mapParamTypes = mapInfo.getTypeCoercer().getOutputType().getType();

    assertEquals(new TypeToken<ImmutableList<String>>() {}.getType(), listParamTypes);
    assertEquals(new TypeToken<ImmutableMap<String, Integer>>() {}.getType(), mapParamTypes);
  }

  @Test
  public void setsValue() throws LabelSyntaxException, EvalException, ParamInfoException {
    SkylarkUserDefinedRule rule = FakeSkylarkUserDefinedRuleFactory.createSimpleRule();
    SkylarkDescriptionArg arg = new SkylarkDescriptionArg(rule);
    SkylarkParamInfo<?> info = new SkylarkParamInfo<>("baz", rule.getAttrs().get("baz"));
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    info.set(
        TestCellPathResolver.get(filesystem).getCellNameResolver(),
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        arg,
        "foo");

    assertEquals("foo", info.get(arg));
    assertEquals("foo", arg.getPostCoercionValue("baz"));
  }

  @Test
  public void failsOnInvalidCoercion()
      throws LabelSyntaxException, EvalException, ParamInfoException {
    SkylarkUserDefinedRule rule = FakeSkylarkUserDefinedRuleFactory.createSimpleRule();
    SkylarkDescriptionArg arg = new SkylarkDescriptionArg(rule);
    SkylarkParamInfo<?> info = new SkylarkParamInfo<>("baz", rule.getAttrs().get("baz"));
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    expected.expect(ParamInfoException.class);
    expected.expectMessage("parameter 'baz': cannot coerce '7' to class java.lang.String");

    info.set(
        TestCellPathResolver.get(filesystem).getCellNameResolver(),
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        arg,
        7);
  }

  @Test
  public void errorsOnGettingANonSetValue() throws LabelSyntaxException, EvalException {
    SkylarkUserDefinedRule rule = FakeSkylarkUserDefinedRuleFactory.createSimpleRule();
    SkylarkDescriptionArg arg = new SkylarkDescriptionArg(rule);
    SkylarkParamInfo<?> info = new SkylarkParamInfo<>("baz", rule.getAttrs().get("baz"));

    expected.expect(NullPointerException.class);

    assertEquals("foo", info.get(arg));
  }

  @Test
  public void returnsImplicitDefaultValue() throws LabelSyntaxException, EvalException {
    SkylarkUserDefinedRule rule = FakeSkylarkUserDefinedRuleFactory.createSimpleRule();
    SkylarkParamInfo<?> info = new SkylarkParamInfo<>("baz", rule.getAttrs().get("baz"));
    SkylarkParamInfo<?> implicitInfo =
        new SkylarkParamInfo<>("_implicit_baz", rule.getAttrs().get("baz"));

    assertEquals(
        rule.getAttrs().get("baz").getPreCoercionDefaultValue(),
        info.getImplicitPreCoercionValue());
    assertEquals("default", implicitInfo.getImplicitPreCoercionValue());
  }
}

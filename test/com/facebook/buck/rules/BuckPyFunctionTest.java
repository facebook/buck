/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.base.Joiner;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public class BuckPyFunctionTest {

  private BuckPyFunction buckPyFunction;

  @Before
  public void setUpMarshaller() {
    buckPyFunction =
        new BuckPyFunction(new ConstructorArgMarshaller(new DefaultTypeCoercerFactory(
            ObjectMappers.newDefaultInstance())));
  }

  public static class NoName {
    public String random;
  }

  @Test
  public void nameWillBeAddedIfMissing() {

    String definition = buckPyFunction.toPythonFunction(
        BuildRuleType.of("bad"),
        new NoName());

    assertTrue(definition.contains("name"));
  }

  public static class NoVis {
    public String random;
  }

  @Test
  public void visibilityWillBeAddedIfMissing() {
    String definition = buckPyFunction.toPythonFunction(
        BuildRuleType.of("bad"),
        new NoVis());

    assertTrue(definition.contains("visibility=[]"));
  }

  public static class Named {
    public String name;
  }

  @Test
  public void shouldOnlyIncludeTheNameFieldOnce() {
    String definition = buckPyFunction.toPythonFunction(
        BuildRuleType.of("named"),
        new Named());

    assertEquals(Joiner.on("\n").join(
        "@provide_for_build",
        "def named(name, autodeps=False, visibility=[], build_env=None):",
        "    add_rule({",
        "        'buck.type': 'named',",
        "        'name': name,",
        "        'autodeps': autodeps,",
        "        'visibility': visibility,",
        "    }, build_env)",
        "",
        ""
    ), definition);
  }

  @TargetName(name = "lollerskates")
  public static class TargetNameOnly {
    public String foobar;
  }

  @Test
  public void testHasDefaultName() {

    String definition = buckPyFunction.toPythonFunction(
        BuildRuleType.of("noname"),
        new TargetNameOnly());

    assertEquals(Joiner.on("\n").join(
            "@provide_for_build",
            "def noname(foobar, autodeps=False, visibility=[], build_env=None):",
            "    add_rule({",
            "        'buck.type': 'noname',",
            "        'name': 'lollerskates',",
            "        'foobar': foobar,",
            "        'autodeps': autodeps,",
            "        'visibility': visibility,",
            "    }, build_env)",
            "",
            ""
        ), definition);
  }

  public static class BadName {
    public int name;
  }

  @Test(expected = HumanReadableException.class)
  public void theNameFieldMustBeAString() {
    buckPyFunction.toPythonFunction(BuildRuleType.of("nope"), new BadName());
  }

  public static class LotsOfOptions {
    public Optional<String> thing;
    public Optional<List<BuildTarget>> targets;
    public Optional<Integer> version;
    public Optional<Boolean> doStuff;
  }

  @Test
  public void optionalFieldsDefaultToAbsent() {
    String definition = buckPyFunction.toPythonFunction(
        BuildRuleType.of("optional"), new LotsOfOptions());

    assertTrue(
        definition,
        definition.contains("do_stuff=None, targets=None, thing=None, version=None"));
  }

  public static class Either {
    // Alphabetical ordering is deliberate.
    public Optional<String> cat;
    public String dog;
    public String egg = "EGG";
    public String fake;
  }

  @Test
  public void optionalFieldsAreListedAfterMandatoryOnes() {
    String definition = buckPyFunction.toPythonFunction(
        BuildRuleType.of("either"),
        new Either());

    assertEquals(Joiner.on("\n").join(
        "@provide_for_build",
        "def either(name, dog, fake, " +
            "cat=None, egg=None, autodeps=False, visibility=[], build_env=None):",
        "    add_rule({",
        "        'buck.type': 'either',",
        "        'name': name,",
        "        'dog': dog,",
        "        'fake': fake,",
        "        'cat': cat,",
        "        'egg': egg,",
        "        'autodeps': autodeps,",
        "        'visibility': visibility,",
        "    }, build_env)",
        "",
        ""
    ), definition);
  }

  public static class Visible {
    public Set<BuildTargetPattern> visibility;
  }

  @Test(expected = HumanReadableException.class)
  public void visibilityOptionsMustNotBeSetAsTheyArePassedInBuildRuleParamsLater() {
    buckPyFunction.toPythonFunction(BuildRuleType.of("nope"), new Visible());
  }

  public static class Dto {
    public String someField;

    @Hint(name = "all_this_was_fields")
    public String hintedField;
  }

  @Test
  public void shouldConvertCamelCaseFieldNameToSnakeCaseParameter() {
    String definition = buckPyFunction.toPythonFunction(
        BuildRuleType.of("case"),
        new Dto());

    assertEquals(Joiner.on("\n").join(
        "@provide_for_build",
        "def case(name, all_this_was_fields, some_field, " +
            "autodeps=False, visibility=[], build_env=None):",
        "    add_rule({",
        "        'buck.type': 'case',",
        "        'name': name,",
        "        'hintedField': all_this_was_fields,",
        "        'someField': some_field,",
        "        'autodeps': autodeps,",
        "        'visibility': visibility,",
        "    }, build_env)",
        "",
        ""
    ), definition);
  }
}

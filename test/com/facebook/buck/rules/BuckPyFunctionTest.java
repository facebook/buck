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
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

@SuppressWarnings("unused") // Many unused fields in sample DTO objects.
public class BuckPyFunctionTest {

  private BuckPyFunction buckPyFunction;

  @Before
  public void setUpMarshaller() {
    Path basePath = Paths.get("example", "path");
    buckPyFunction = new BuckPyFunction(new ConstructorArgMarshaller(basePath));
  }

  @Test
  public void nameWillBeAddedIfMissing() {
    class NoName { public String random; }

    String definition = buckPyFunction.toPythonFunction(new BuildRuleType("bad"), new NoName());

    assertTrue(definition.contains("name"));
  }

  @Test
  public void visibilityWillBeAddedIfMissing() {
    class NoVis { public String random; }

    String definition = buckPyFunction.toPythonFunction(new BuildRuleType("bad"), new NoVis());

    assertTrue(definition.contains("visibility=[]"));
  }

  @Test
  public void shouldOnlyIncludeTheNameFieldOnce() {
    class Named { public String name; }

    String definition = buckPyFunction.toPythonFunction(new BuildRuleType("named"), new Named());

    assertEquals(Joiner.on("\n").join(
        "@provide_for_build",
        "def named(name, visibility=[], build_env=None):",
        "  add_rule({",
        "    'type' : 'named',",
        "    'name' : name,",
        "    'visibility' : visibility,",
        "  }, build_env)",
        "",
        ""
    ), definition);
  }

  @Test(expected = HumanReadableException.class)
  public void theNameFieldMustBeAString() {
    class BadName { public int name; }

    buckPyFunction.toPythonFunction(new BuildRuleType("nope"), new BadName());
  }

  @Test
  public void optionalFieldsAreGivenSensibleDefaultValues() {
    class LotsOfOptions {
      public Optional<String> thing;
      public Optional<List<BuildTarget>> targets;
      public Optional<Integer> version;
    }

    String definition = buckPyFunction.toPythonFunction(
        new BuildRuleType("optional"), new LotsOfOptions());

    assertTrue(definition, definition.contains("targets=[], thing='', version=0"));
  }

  @Test
  public void optionalFieldsAreListedAfterMandatoryOnes() {
    class Either {
      // Alphabetical ordering is deliberate.
      public Optional<String> cat;
      public String dog;
      public Optional<String> egg;
      public String fake;
    }

    String definition = buckPyFunction.toPythonFunction(new BuildRuleType("either"), new Either());

    assertEquals(Joiner.on("\n").join(
        "@provide_for_build",
        "def either(name, dog, fake, cat='', egg='', visibility=[], build_env=None):",
        "  add_rule({",
        "    'type' : 'either',",
        "    'name' : name,",
        "    'dog' : dog,",
        "    'fake' : fake,",
        "    'cat' : cat,",
        "    'egg' : egg,",
        "    'visibility' : visibility,",
        "  }, build_env)",
        "",
        ""
    ), definition);
  }

  @Test(expected = HumanReadableException.class)
  public void visibilityOptionsMustNotBeSetAsTheyArePassedInBuildRuleParamsLater() {
    class Visible {
      public Set<BuildTargetPattern> visibility;
    }

    buckPyFunction.toPythonFunction(new BuildRuleType("nope"), new Visible());
  }

  @Test
  public void shouldConvertCamelCaseFieldNameToSnakeCaseParameter() {
    class Dto {
      public String someField;

      @Hint(name = "all_this_was_fields")
      public String hintedField;
    }

    String definition = buckPyFunction.toPythonFunction(new BuildRuleType("case"), new Dto());

    assertEquals(Joiner.on("\n").join(
        "@provide_for_build",
        "def case(name, all_this_was_fields, some_field, visibility=[], build_env=None):",
        "  add_rule({",
        "    'type' : 'case',",
        "    'name' : name,",
        "    'hintedField' : all_this_was_fields,",
        "    'someField' : some_field,",
        "    'visibility' : visibility,",
        "  }, build_env)",
        "",
        ""
    ), definition);
  }
}

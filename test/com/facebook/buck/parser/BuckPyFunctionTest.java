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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.description.arg.DataTransferObject;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcher;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.parser.function.BuckPyFunction;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;
import org.junit.Before;
import org.junit.Test;

public class BuckPyFunctionTest {

  private BuckPyFunction buckPyFunction;

  @Before
  public void setUpMarshaller() {
    buckPyFunction = new BuckPyFunction(new DefaultTypeCoercerFactory());
  }

  @RuleArg
  abstract static class AbstractNoName implements DataTransferObject {
    abstract String getRandom();
  }

  @Test
  public void nameWillBeAddedIfMissing() {

    String definition =
        buckPyFunction.toPythonFunction(RuleType.of("bad", RuleType.Kind.BUILD), NoName.class);

    assertTrue(definition.contains("name"));
  }

  @RuleArg
  abstract static class AbstractNoVis implements DataTransferObject {
    abstract String getRandom();
  }

  @Test
  public void visibilityWillBeAddedIfMissing() {
    String definition =
        buckPyFunction.toPythonFunction(RuleType.of("bad", RuleType.Kind.BUILD), NoVis.class);

    assertTrue(definition.contains("visibility=None"));
  }

  @RuleArg
  abstract static class AbstractNamed implements DataTransferObject {}

  @Test
  public void shouldOnlyIncludeTheNameFieldOnce() {
    String definition =
        buckPyFunction.toPythonFunction(RuleType.of("named", RuleType.Kind.BUILD), Named.class);

    assertEquals(
        Joiner.on("\n")
            .join(
                "@provide_as_native_rule",
                "def named(name, visibility=None, within_view=None, build_env=None):",
                "    add_rule({",
                "        'buck.type': 'named',",
                "        'name': name,",
                "        'visibility': visibility,",
                "        'within_view': within_view,",
                "    }, build_env)",
                "",
                ""),
        definition);
  }

  @RuleArg
  abstract static class AbstractLotsOfOptions implements DataTransferObject {
    abstract Optional<String> getThing();

    abstract Optional<List<BuildTarget>> getTargets();

    @Value.Default
    List<String> getStrings() {
      return ImmutableList.of("123");
    }

    abstract Optional<Boolean> isDoStuff();

    @Value.Default
    boolean isDoSomething() {
      return true;
    }
  }

  @Test
  public void optionalFieldsDefaultToAbsent() {
    String definition =
        buckPyFunction.toPythonFunction(
            RuleType.of("optional", RuleType.Kind.BUILD), LotsOfOptions.class);

    assertTrue(
        definition,
        definition.contains(
            "do_something=None, do_stuff=None, strings=None, targets=None, " + "thing=None"));
  }

  @RuleArg
  abstract static class AbstractEither implements DataTransferObject {
    // Alphabetical ordering is deliberate.
    abstract Optional<String> getCat();

    abstract String getDog();

    @Value.Default
    String getEgg() {
      return "EGG";
    }

    abstract String getFake();
  }

  @Test
  public void optionalFieldsAreListedAfterMandatoryOnes() {
    String definition =
        buckPyFunction.toPythonFunction(RuleType.of("either", RuleType.Kind.BUILD), Either.class);

    assertEquals(
        Joiner.on("\n")
            .join(
                "@provide_as_native_rule",
                "def either(name, dog, fake, cat=None, egg=None, "
                    + "visibility=None, within_view=None, build_env=None):",
                "    add_rule({",
                "        'buck.type': 'either',",
                "        'name': name,",
                "        'dog': dog,",
                "        'fake': fake,",
                "        'cat': cat,",
                "        'egg': egg,",
                "        'visibility': visibility,",
                "        'within_view': within_view,",
                "    }, build_env)",
                "",
                ""),
        definition);
  }

  @RuleArg
  abstract static class AbstractVisible implements DataTransferObject {
    abstract Set<BuildTargetMatcher> getVisibility();
  }

  @Test(expected = HumanReadableException.class)
  public void visibilityOptionsMustNotBeSetAsTheyArePassedInBuildRuleParamsLater() {
    buckPyFunction.toPythonFunction(RuleType.of("nope", RuleType.Kind.BUILD), Visible.class);
  }

  @RuleArg
  abstract static class AbstractDto implements DataTransferObject {
    abstract String getSomeField();
  }

  @Test
  public void shouldConvertCamelCaseFieldNameToSnakeCaseParameter() {
    String definition =
        buckPyFunction.toPythonFunction(RuleType.of("case", RuleType.Kind.BUILD), Dto.class);

    assertEquals(
        Joiner.on("\n")
            .join(
                "@provide_as_native_rule",
                "def case(name, some_field, "
                    + "visibility=None, within_view=None, build_env=None):",
                "    add_rule({",
                "        'buck.type': 'case',",
                "        'name': name,",
                "        'someField': some_field,",
                "        'visibility': visibility,",
                "        'within_view': within_view,",
                "    }, build_env)",
                "",
                ""),
        definition);
  }
}

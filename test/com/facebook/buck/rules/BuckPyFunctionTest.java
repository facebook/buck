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
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
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
    buckPyFunction = new BuckPyFunction(new DefaultTypeCoercerFactory(), CoercedTypeCache.INSTANCE);
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractNoName {
    abstract String getRandom();
  }

  @Test
  public void nameWillBeAddedIfMissing() {

    String definition = buckPyFunction.toPythonFunction(BuildRuleType.of("bad"), NoName.class);

    assertTrue(definition.contains("name"));
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractNoVis {
    abstract String getRandom();
  }

  @Test
  public void visibilityWillBeAddedIfMissing() {
    String definition = buckPyFunction.toPythonFunction(BuildRuleType.of("bad"), NoVis.class);

    assertTrue(definition.contains("visibility=None"));
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractNamed {
    abstract String getName();
  }

  @Test
  public void shouldOnlyIncludeTheNameFieldOnce() {
    String definition = buckPyFunction.toPythonFunction(BuildRuleType.of("named"), Named.class);

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

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractLotsOfOptions {
    abstract Optional<String> getThing();

    abstract Optional<List<BuildTarget>> getTargets();

    @Value.Default
    List<String> getStrings() {
      return ImmutableList.of("123");
    }

    abstract Optional<Integer> getVersion();

    abstract Optional<Boolean> isDoStuff();

    @Value.Default
    boolean isDoSomething() {
      return true;
    }
  }

  @Test
  public void optionalFieldsDefaultToAbsent() {
    String definition =
        buckPyFunction.toPythonFunction(BuildRuleType.of("optional"), LotsOfOptions.class);

    assertTrue(
        definition,
        definition.contains(
            "do_something=None, do_stuff=None, strings=None, targets=None, "
                + "thing=None, version=None"));
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractEither {
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
    String definition = buckPyFunction.toPythonFunction(BuildRuleType.of("either"), Either.class);

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

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractVisible {
    abstract Set<BuildTargetPattern> getVisibility();
  }

  @Test(expected = HumanReadableException.class)
  public void visibilityOptionsMustNotBeSetAsTheyArePassedInBuildRuleParamsLater() {
    buckPyFunction.toPythonFunction(BuildRuleType.of("nope"), Visible.class);
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDto {
    abstract String getSomeField();
  }

  @Test
  public void shouldConvertCamelCaseFieldNameToSnakeCaseParameter() {
    String definition = buckPyFunction.toPythonFunction(BuildRuleType.of("case"), Dto.class);

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

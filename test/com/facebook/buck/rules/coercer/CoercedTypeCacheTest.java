/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Optional;
import org.immutables.value.Value;
import org.junit.Test;

public class CoercedTypeCacheTest {
  @Test
  public void requiredIsNotOptional() {
    assertFalse(getParamInfo("required").isOptional());
  }

  @Test
  public void optionalIsOptional() {
    assertTrue(getParamInfo("optional").isOptional());
  }

  @Test
  public void optionalIsInheritedOptional() {
    assertTrue(getParamInfo("interfaceOptional").isOptional());
  }

  @Test
  public void defaultValuesAreOptional() {
    assertTrue(getParamInfo("default").isOptional());
  }

  @Test
  public void defaultValuesAreOptionalThroughInheritence() {
    assertTrue(getParamInfo("interfaceDefault").isOptional());
  }

  @Test
  public void getName() {
    assertEquals(
        ImmutableSortedSet.of(
            "consistentOverriddenInterfaceNonDep",
            "consistentOverriddenInterfaceNonInput",
            "default",
            "interfaceDefault",
            "interfaceNonDep",
            "interfaceNonInput",
            "interfaceOptional",
            "optional",
            "overriddenInterfaceNonDep",
            "overriddenInterfaceNonInput",
            "nonDep",
            "nonInput",
            "required"),
        ImmutableSortedSet.copyOf(
            CoercedTypeCache.extractForImmutableBuilder(
                    Dto.Builder.class, new DefaultTypeCoercerFactory())
                .keySet()));
  }

  @Test
  public void getPythonName() {
    assertEquals(
        ImmutableSortedSet.of(
            "consistent_overridden_interface_non_dep",
            "consistent_overridden_interface_non_input",
            "default",
            "interface_default",
            "interface_non_dep",
            "interface_non_input",
            "interface_optional",
            "optional",
            "overridden_interface_non_dep",
            "overridden_interface_non_input",
            "non_dep",
            "non_input",
            "required"),
        CoercedTypeCache.extractForImmutableBuilder(
                Dto.Builder.class, new DefaultTypeCoercerFactory())
            .values().stream()
            .map(ParamInfo::getPythonName)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
  }

  @Test
  public void isDep() {
    assertFalse(getParamInfo("nonDep").isDep());
    assertTrue(getParamInfo("optional").isDep());
  }

  @Test
  public void isDepInherited() {
    assertFalse(getParamInfo("interfaceNonDep").isDep());
    assertFalse(getParamInfo("consistentOverriddenInterfaceNonDep").isDep());
    assertTrue(getParamInfo("overriddenInterfaceNonDep").isDep());
    assertTrue(getParamInfo("interfaceOptional").isDep());
  }

  @Test
  public void isInput() {
    assertFalse(getParamInfo("nonInput").isInput());
    assertTrue(getParamInfo("optional").isInput());
  }

  @Test
  public void isInputInherited() {
    assertFalse(getParamInfo("interfaceNonInput").isInput());
    assertFalse(getParamInfo("consistentOverriddenInterfaceNonInput").isInput());
    assertTrue(getParamInfo("overriddenInterfaceNonInput").isInput());
    assertTrue(getParamInfo("interfaceOptional").isInput());
  }

  interface DtoInterface {
    Optional<String> getInterfaceOptional();

    @Value.Default
    default String getInterfaceDefault() {
      return "blue";
    }

    @Hint(isDep = false)
    String getInterfaceNonDep();

    @Hint(isDep = false)
    String getOverriddenInterfaceNonDep();

    @Hint(isDep = false)
    String getConsistentOverriddenInterfaceNonDep();

    @Hint(isInput = false)
    String getInterfaceNonInput();

    @Hint(isInput = false)
    String getOverriddenInterfaceNonInput();

    @Hint(isInput = false)
    String getConsistentOverriddenInterfaceNonInput();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDto implements DtoInterface {
    abstract Optional<String> getOptional();

    abstract String getRequired();

    @Value.Default
    String getDefault() {
      return "purple";
    }

    @Hint(isDep = false)
    abstract String getNonDep();

    @Override
    public abstract String getOverriddenInterfaceNonDep();

    @Override
    @Hint(isDep = false)
    public abstract String getConsistentOverriddenInterfaceNonDep();

    @Hint(isInput = false)
    abstract String getNonInput();

    @Override
    public abstract String getOverriddenInterfaceNonInput();

    @Override
    @Hint(isInput = false)
    public abstract String getConsistentOverriddenInterfaceNonInput();
  }

  private static ParamInfo getParamInfo(String name) {
    return CoercedTypeCache.extractForImmutableBuilder(
            Dto.Builder.class, new DefaultTypeCoercerFactory())
        .get(name);
  }
}

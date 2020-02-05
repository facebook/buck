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

package com.facebook.buck.core.model;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Preconditions;
import org.immutables.value.Value;

@Value.Immutable(builder = false, copy = false, prehash = false, intern = true)
@BuckStyleValue
@JsonDeserialize(as = ImmutableRuleType.class)
public abstract class RuleType {

  /** The kind of a rule type. */
  public enum Kind {
    /** Build rule types can be used during the build to produce build artifacts. */
    BUILD,
    /** Configuration rule types can be used during configuration phase. */
    CONFIGURATION,
  }

  /** @return the name as displayed in a build file, such as "java_library" */
  @JsonProperty("name")
  public abstract String getName();

  /** @return the kind of this type. */
  @JsonProperty("kind")
  public abstract Kind getKind();

  @Value.Derived
  @JsonIgnore
  public boolean isTestRule() {
    return getName().endsWith("_test");
  }

  /**
   * @return {@code true} if this rule type is a build rule, {@code false} otherwise (for example,
   *     for configuration rule types).
   */
  @Value.Derived
  @JsonIgnore
  public boolean isBuildRule() {
    return getKind() == Kind.BUILD;
  }

  @Value.Check
  protected void check() {
    String name = getName();
    Preconditions.checkArgument(name.toLowerCase().equals(name));
  }

  @Override
  public String toString() {
    return getName();
  }

  public static RuleType of(String name, RuleType.Kind kind) {
    return ImmutableRuleType.of(name, kind);
  }
}

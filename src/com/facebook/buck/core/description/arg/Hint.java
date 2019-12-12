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

package com.facebook.buck.core.description.arg;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Represents hints given when deal with the value of a type returned by {@link
 * com.facebook.buck.core.rules.DescriptionWithTargetGraph#getConstructorArgType()}.
 */
@Retention(RUNTIME)
@Target({FIELD, METHOD})
public @interface Hint {
  boolean DEFAULT_IS_DEP = true;
  boolean DEFAULT_IS_INPUT = true;
  boolean DEFAULT_IS_TARGET_GRAPH_ONLY_DEP = false;
  boolean DEFAULT_IS_CONFIGURABLE = true;
  boolean DEFAULT_SPLIT_CONFIGURATION = false;
  boolean DEFAULT_EXEC_CONFIGURATION = false;

  /** @return Whether to search the field's value for dependencies */
  boolean isDep() default DEFAULT_IS_DEP;

  /** @return Whether to use the field's value as an input */
  boolean isInput() default DEFAULT_IS_INPUT;

  /**
   * @return Whether to add the field's value to the target graph, but do not automatically
   *     propagate it to the build rule (action graph). During action graph construction, build
   *     rules can still decide to add them to action graph based on some condition. Build rules
   *     must explicitly handle these dependencies if they should be used during build.
   *     <p>For example, rules that support platform-specific dependencies must explicitly include
   *     dependencies matching target platform into the action graph.
   */
  boolean isTargetGraphOnlyDep() default DEFAULT_IS_TARGET_GRAPH_ONLY_DEP;

  /** @return Whether an attribute can be configured using {@code select}. */
  boolean isConfigurable() default DEFAULT_IS_CONFIGURABLE;

  /**
   * Indicates that target configuration needs to be split.
   *
   * <p>The target configuration will be transformed into multiple other configurations and every
   * target in this attribute (target-based object) will be created for every configuration and the
   * resulting list will be stored in this attribute.
   *
   * <p>Note that this logic only applies when target configuration supports transformation into
   * multiple configurations and the attribute type supports concatenation.
   */
  boolean splitConfiguration() default DEFAULT_SPLIT_CONFIGURATION;

  /**
   * Indicates that execution configuration (as opposed to target configuration) should be used when
   * resolving targets.
   *
   * <p>This is used for example in {@code cxx_toolchain} rule: toolchain itself need to be created
   * with target configuration, but if it specifies c compiler as a build target, that target need
   * to be built for execution configuration.
   */
  boolean execConfiguration() default DEFAULT_EXEC_CONFIGURATION;
}

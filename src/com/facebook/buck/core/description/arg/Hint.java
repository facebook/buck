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

package com.facebook.buck.core.description.arg;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Represents hints given when deal with the value of a type returned by {@link
 * com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph#getConstructorArgType()}.
 */
@Retention(RUNTIME)
@Target({FIELD, METHOD})
public @interface Hint {
  boolean DEFAULT_IS_DEP = true;
  boolean DEFAULT_IS_INPUT = true;
  boolean DEFAULT_IS_TARGET_GRAPH_ONLY_DEP = false;

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
}

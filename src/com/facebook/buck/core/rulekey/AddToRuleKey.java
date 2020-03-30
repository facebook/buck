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

package com.facebook.buck.core.rulekey;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Indicates that a field or method of a class should be added to rulekeys when an instance of that
 * class is added to a rulekey.
 *
 * <p>This annotation also indicates that we should derive inputs, deps and serialization
 * automatically from this field/method.
 *
 * <p>Annotating a method with @AddToRuleKey should only be used for Immutable objects.
 *
 * <p>In general, this should be added to all fields unless there's a good reason not to add it. In
 * that case, annotated the field/method with @{@link ExcludeFromRuleKey} to indicate that not
 * adding it to the keys is intentional.
 */
@Retention(RUNTIME)
@Target({FIELD, METHOD})
public @interface AddToRuleKey {

  /**
   * This causes us to add the value to the rulekey in its stringified form (i.e. we call toString()
   * on it).
   */
  boolean stringify() default false;
}

/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rulekey;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a field/method of a class explicitly excluded from rulekeys. Usage of this should be very
 * rare. In general, we consider uses of it to indicate likely correctness problems. Instead
 * annotate the field with @{@link AddToRuleKey}.
 */
@Retention(RUNTIME)
@Target({FIELD, METHOD})
public @interface ExcludeFromRuleKey {
  /**
   * This indicates the reason we exclude a value from rulekeys. It's currently only used for
   * logging.
   */
  String value() default "";

  /**
   * We really do think that using this annotation indicates a likely source of problems. By
   * default, the first time we encounter such an annotated field/method, we will log that
   * information to the buck log.
   */
  boolean shouldReport() default true;
}

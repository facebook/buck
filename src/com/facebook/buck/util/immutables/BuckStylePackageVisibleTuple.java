/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.immutables;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.immutables.value.Value;

/**
 * Tuple-style objects conforming to {@link BuckStyleImmutable} naming style.
 *
 * <p>Tuple-style objects have all attributes as constructor parameters, and do not have builders.
 *
 * @see <a href="http://immutables.github.io/immutable.html#tuples">Immutable user guide</a>
 */
@Value.Style(
  typeImmutable = "*",
  typeAbstract = "Abstract*",
  get = {"is*", "get*"},
  init = "set*",
  visibility = Value.Style.ImplementationVisibility.PACKAGE,
  allParameters = true,
  defaults = @Value.Immutable(builder = false),
  forceJacksonPropertyNames = false,
  additionalJsonAnnotations = {JsonNaming.class}
)
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface BuckStylePackageVisibleTuple {}

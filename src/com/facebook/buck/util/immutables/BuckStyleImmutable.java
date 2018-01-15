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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.immutables.value.Value;

/**
 * Style for code-generated Immutables.org immutable value types which:
 *
 * <ol>
 *   <li>Does not add the Immutable prefix on generated classes
 *   <li>Strips off the Abstract name prefix when processing the parent interface or abstract class
 *   <li>Supports isFoo() / getFoo() getters in the parent interface or abstract class
 *   <li>Supports setFoo() setters in the parent interface or abstract class
 *   <li>Ensures the generated class is public (even if the parent interface or abstract class is
 *       package private)
 *   <li>Disable generating with* methods by default, as they cause excessive codegen. Enable it on
 *       a case by case basis by setting {@code copy = true} explicitly.
 * </ol>
 */
@Value.Style(
  typeImmutable = "*",
  typeAbstract = "Abstract*",
  get = {"is*", "get*"},
  init = "set*",
  visibility = Value.Style.ImplementationVisibility.PUBLIC,
  forceJacksonPropertyNames = false,
  defaults = @Value.Immutable(copy = false)
)
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface BuckStyleImmutable {}

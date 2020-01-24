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

package com.facebook.buck.core.util.immutables;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.immutables.value.Value;

/**
 * Immutable generator for data transfer objects. These are objects that are, or belong on rule
 * args. These types can be coerced by the parser from python/starlark into java objects.
 *
 * <p>This style:
 *
 * <ol>
 *   <li>Does not add the Immutable prefix on generated classes
 *   <li>Strips off the Abstract name prefix when processing the parent interface or abstract class
 *   <li>Supports isFoo() / getFoo() getters in the parent interface or abstract class
 *   <li>Supports setFoo() setters in the parent interface or abstract class
 *   <li>Ensures the generated class is public (even if the parent interface or abstract class is
 *       package private)
 *   <li>Disable generating with* methods by default, as they cause excessive codegen.
 * </ol>
 */
@Value.Style(
    typeImmutable = "*",
    typeAbstract = "Abstract*",
    get = {"is*", "get*"},
    init = "set*",
    visibility = Value.Style.ImplementationVisibility.PUBLIC,
    forceJacksonPropertyNames = false,
    defaults = @Value.Immutable(copy = false))
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RuleArg {}

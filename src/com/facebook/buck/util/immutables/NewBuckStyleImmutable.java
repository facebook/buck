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

import org.immutables.value.Value;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Style for code-generated Immutables.org immutable value types which:</p>
 *
 * <ol>
 * <li>Does not add the Immutable prefix on generated classes</li>
 * <li>Strips off the Abstract name prefix when processing the parent interface or abstract
 * class</li>
 * <li>Supports isFoo() / getFoo() getters in the parent interface or abstract class</li>
 * <li>Supports setFoo() setters in the parent interface or abstract class</li>
 * </ol>
 *
 * <p>Once we upgrade to Immutables.org 2.0 (pending a fix for
 * https://github.com/immutables/immutables/issues/90) we can add:</p>
 *
 * <ol start="5">
 * <li>Ensures the generated class is public (even if the parent interface or abstract class
 * is package private)</li>
 * </ol>
 *
 * <p>Until then, if you want your abstract type to be package-private, you'll
 * need to declare it with:</p>
 *
 * <pre>
 *   {@literal @}Value.Immutable(visibility=Value.ImplementationVisibility.PUBLIC)
 *   {@literal @}NewBuckStyleImmutable
 *   interface AbstractFoo { ... }
 * </pre>
 */
@Value.Style(
    typeImmutable = "*",
    typeAbstract = "Abstract*",
    get = {"is*", "get*"},
    init = "set*"
    // TODO(user): Enable this when we upgrade to Immutables.org 2.0.
    /* visibility = Value.Style.ImplementationVisibility.PUBLIC */)
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface NewBuckStyleImmutable {}

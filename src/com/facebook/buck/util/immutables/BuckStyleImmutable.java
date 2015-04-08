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
 * <li>Ensures the generated class is public (even if the parent interface or abstract class
 * is package private)</li>
 */
@Value.Style(
    typeImmutable = "*",
    typeAbstract = "Abstract*",
    get = {"is*", "get*"},
    init = "set*",
    visibility = Value.Style.ImplementationVisibility.PUBLIC)
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface BuckStyleImmutable {}

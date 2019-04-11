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

package com.facebook.buck.core.util.immutables;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.immutables.value.Value;

/**
 * Value-style objects conforming to {@link BuckStyleImmutable} naming style.
 *
 * <p>Value-style objects have all attributes as constructor parameters, do not have builders,
 * "with" methods, "copy" methods, or "of" methods.
 *
 * <p>When using this annotation, it's unnecessary to explicitly use @Value.Immutable.
 *
 * <p>If the Value.Immutable behavior needs to be customized, you must explicitly set the values set
 * in the defaults here (otherwise a linter will yell at you).
 *
 * @see <a href="http://immutables.github.io/immutable.html#tuples">Immutable user guide</a>
 */
@Value.Style(
    get = {"is*", "get*"},
    init = "set*",
    of = "new",
    visibility = Value.Style.ImplementationVisibility.SAME,
    allParameters = true,
    defaults = @Value.Immutable(builder = false, copy = false),
    forceJacksonPropertyNames = false,
    additionalJsonAnnotations = {JsonNaming.class})
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface BuckStyleValue {}

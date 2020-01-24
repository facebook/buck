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

package com.facebook.buck.core.rules.providers.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.immutables.value.Value;

/**
 * Annotation marking a class as an immutable built in implementation of {@link
 * com.facebook.buck.core.rules.providers.ProviderInfo}.
 *
 * <p>This uses the immutable library to generate an implementation class that conforms to the
 * requirements of providers declared in java.
 */
@Value.Style(
    init = "set*",
    of = "new",
    visibility = Value.Style.ImplementationVisibility.PUBLIC,
    allParameters = true,
    defaults = @Value.Immutable(builder = false, copy = false, prehash = false))
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ImmutableInfo {

  /**
   * TODO this is here because java reflection doesn't expose parameter names, and methods can't be
   * retrieved in order of declaration. We should just have some custom annotation processor to
   * generate this.
   *
   * @return an array of Strings that represent the names of the constructor parameters in order of
   *     declared methods. This should be in the lower underscore naming convention for python.
   */
  String[] args();

  /**
   * @return an array of string representations of default values to use in the Starlark interpreter
   *     if a kwarg is not provided.
   */
  String[] defaultSkylarkValues() default {};

  /** @return an array of fields that should be noneable */
  // TODO(pjameson): T60486516 this should probably go away in favor of inferring noneable things
  // from an Optional or SkylarkOptional field
  String[] noneable() default {};
}

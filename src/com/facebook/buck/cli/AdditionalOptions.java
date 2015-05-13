/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * If this annotation is used on a field in one of the {@code ..Options} classes (e.g. subclasses of
 * {@link AbstractCommand}), then a {@link AdditionalOptionsCmdLineParser} will recursively
 * look for options/arguments in the (class of the) field and add them to the parser, almost
 * allowing traits for options.
 * <p>
 * Usage example:
 * <pre>
 * class MyOptions{
 *   &#64;AdditionalOptions
 *   public ReusableSubOptions subOptions;
 *   &#64;Option(...)
 *   public String someOption;
 * }
 * class ReusableSubOptions{
 *   &#64;Option(...)
 *   public String someSubOption;
 * }
 * </pre>
 */
@Retention(RUNTIME)
@Target({FIELD})
public @interface AdditionalOptions {

}

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

package com.facebook.buck.cli;

import com.facebook.buck.util.ExitCode;

/** Top-level exception processor. This can be used to do eager processing of exceptions. */
public interface ExceptionProcessor {
  /**
   * Processes an exception and converts it to an ExitCode.
   *
   * <p>This would be used in a case like this:
   *
   * <pre>{@code
   * ExitCode outerFunction() {
   *   try {
   *     innerFunction();
   *   } catch (Exception e) {
   *     do some processing of exception
   *     return someExitCodeDerivedFromTheException;
   *   }
   * }
   *
   * ExitCode innerFunction() throws Exception {
   *   do stuff
   * }
   * }</pre>
   *
   * In a case like that, a ExceptionProcessor can be introduced that encodes the "do some
   * processing of exception" and passed to innerFunction so that it can eagerly do that processing.
   *
   * <p>For example, that processing could be logging or printing to the console and using an
   * ExceptionProcessor could allow that printing to happen sooner than it would if the exception
   * were just propagated.
   */
  ExitCode processException(Exception e) throws Exception;
}

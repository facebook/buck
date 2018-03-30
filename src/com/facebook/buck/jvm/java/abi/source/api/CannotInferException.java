/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi.source.api;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

public class CannotInferException extends RuntimeException {
  public CannotInferException(String what, Element owner) {
    super(formatMessage(what, owner));
  }

  public CannotInferException(String what, TypeMirror owner) {
    super(formatMessage(what, owner));
  }

  private static String formatMessage(String what, Object owner) {
    return String.format(
        "Buck had to infer the existence of %1$s for source-only ABI generation, and thus cannot know the %2$s of the type.\n"
            + "One of three things is happening:\n"
            + "  1. The BUCK file is missing a dependency for %1$s\n"
            + "  2. The module containing %1$s needs to be marked with required_for_source_only_abi or referenced as a source_only_abi_dep\n"
            + "  3. An annotatin processor is accessing a type that it shouldn't be",
        owner, what);
  }
}

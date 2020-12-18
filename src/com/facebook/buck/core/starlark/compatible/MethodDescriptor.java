/*
 * Portions Copyright (c) Facebook, Inc. and its affiliates.
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

// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.facebook.buck.core.starlark.compatible;

import java.util.Arrays;
import net.starlark.java.annot.StarlarkMethod;

/**
 * A value class to store Methods with their corresponding {@link StarlarkMethod} annotation
 * metadata. This is needed because the annotation is sometimes in a superclass.
 *
 * <p>The annotation metadata is duplicated in this class to avoid usage of Java dynamic proxies
 * which are ~7× slower.
 */
final class MethodDescriptor {

  private final String name;
  private final ParamDescriptor[] parameters;

  private MethodDescriptor(String name, ParamDescriptor[] parameters) {
    this.name = name;
    this.parameters = parameters;
  }

  /** @return Starlark method descriptor for provided Java method and signature annotation. */
  public static MethodDescriptor of(BuckStarlarkCallable annotation) {
    return new MethodDescriptor(
        annotation.name(),
        Arrays.stream(annotation.parameters())
            .map(ParamDescriptor::of)
            .toArray(ParamDescriptor[]::new));
  }

  /** @see StarlarkMethod#name() */
  public String getName() {
    return name;
  }

  /** @see StarlarkMethod#parameters() */
  public ParamDescriptor[] getParameters() {
    return parameters;
  }

  /** Returns the index of the named parameter or -1 if not found. */
  public int getParameterIndex(String name) {
    for (int i = 0; i < parameters.length; i++) {
      if (parameters[i].getName().equals(name)) {
        return i;
      }
    }
    return -1;
  }
}

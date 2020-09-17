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

package com.facebook.buck.core.starlark.compatible;

import com.facebook.buck.core.model.label.Label;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.syntax.Module;
import com.google.devtools.build.lib.syntax.StarlarkThread;

/**
 * Utilities to work with {@link com.google.devtools.build.lib.syntax.Module} in Buck embedding of
 * Starlark.
 */
public class BuckStarlarkModule {

  /** Set Buck-specific {@link Module} data. */
  public static void setClientData(Module module, Label label) {
    module.setClientData(label);
  }

  /** Fetch Buck-specific {@link Module} data. */
  private static Label getClientData(Module module) {
    Label label = (Label) module.getClientData();
    Preconditions.checkState(label != null, "label must be set for a Module");
    return label;
  }

  /** Buck-local version of {@link Module#ofInnermostEnclosingStarlarkFunction(StarlarkThread)}. */
  public static Label ofInnermostEnclosingStarlarkFunction(StarlarkThread thread) {
    Module module = Module.ofInnermostEnclosingStarlarkFunction(thread);
    Preconditions.checkState(module != null, "we are not in a function");
    return getClientData(module);
  }
}

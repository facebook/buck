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
import net.starlark.java.eval.Module;

/** Utilities to work with {@link Module} in Buck embedding of Starlark. */
public class BuckStarlarkModule {

  /** Set Buck-specific {@link Module} data. */
  public static void setClientData(Module module, Label label) {
    Preconditions.checkState(module.getClientData() == null);
    module.setClientData(label);
  }
}

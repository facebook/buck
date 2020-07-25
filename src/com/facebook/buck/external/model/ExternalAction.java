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

package com.facebook.buck.external.model;

import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.collect.ImmutableList;

/**
 * An action that can be built in a separate process from buck's core. Its build steps does not know
 * about core buck concepts.
 *
 * <p>{@link ExternalAction}s are instantiated via reflection with a default empty constructor.
 */
public interface ExternalAction {

  /** Returns a list of {@link IsolatedStep} instances associated with this buildable. */
  ImmutableList<IsolatedStep> getSteps(BuildableCommand buildableCommand);
}

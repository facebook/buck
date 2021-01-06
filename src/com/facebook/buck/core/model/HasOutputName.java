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

package com.facebook.buck.core.model;

/**
 * Interface for {@link com.facebook.buck.core.rules.BuildRule} instances which generate sources
 * with logical "names" (e.g. Python modules).
 */
public interface HasOutputName {
  /**
   * Returns an output name for the build target associated with the given output label. Not
   * necessarily a path relative to any directory
   *
   * @throws {@link RuntimeException} if the given output label is invalid and is not associated
   *     with an output
   *     <p>TODO(irenewchen): Convert this to ImmutableSet<String>
   */
  String getOutputName(OutputLabel outputLabel);
}

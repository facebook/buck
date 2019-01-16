/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.versions;

import com.facebook.buck.core.cell.CellPathResolver;
import java.util.Optional;

/**
 * Interface for objects which defined how they should be translated in constructor args for
 * versioning.
 */
public interface TargetTranslatable<T> {

  /** @return if any changes are required, return the translated object. */
  Optional<T> translateTargets(
      CellPathResolver cellPathResolver, String targetBaseName, TargetNodeTranslator translator);
}

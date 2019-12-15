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

package com.facebook.buck.features.python;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import org.immutables.value.Value;

/**
 * A {@link PythonComponents} which wraps a directory containing components to add to a top-level
 * Python binary.
 */
// TODO(agallagher): This directory can contain more than just "modules" (e.g. "resources"), so it'd
//  be nice to find a way to handle this properly.
@Value.Immutable
@BuckStyleTuple
abstract class AbstractPythonModuleDirComponents implements PythonComponents {

  // TODO(agallagher): We hash the entire dir contents even though, when creating symlinks, we only
  //  actually really care about the directory structure.  Ideally, we'd have a way to model this
  //  via rule key hashing to avoid unnecessary rebuilds.
  @AddToRuleKey
  abstract SourcePath getDirectory();
}

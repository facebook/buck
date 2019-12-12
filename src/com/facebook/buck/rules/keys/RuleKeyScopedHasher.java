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

package com.facebook.buck.rules.keys;

import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.util.Scope;
import java.nio.file.Path;

/**
 * Used to construct rulekey "scopes". The major use of these is to avoid adding meta-information
 * about the scope if the scope doesn't add anything (i.e. a key scope will add a key only if
 * something else is added to the rulekey within that scope).
 */
public interface RuleKeyScopedHasher {
  Scope keyScope(String key);

  Scope pathKeyScope(Path key);

  Scope wrapperScope(RuleKeyHasher.Wrapper wrapper);

  ContainerScope containerScope(RuleKeyHasher.Container container);

  interface ContainerScope extends Scope {
    Scope elementScope();
  }
}

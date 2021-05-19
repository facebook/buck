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

package net.starlark.java.syntax;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;

/**
 * Common place to intern strings in Starlark interpreter.
 *
 * <p>Interned strings are much faster to lookup, which is important, for example, when evaluating
 * expression {@code foo.bar}.
 */
public class StarlarkStringInterner {
  private StarlarkStringInterner() {}

  private static final Interner<String> INTERNER = Interners.newWeakInterner();

  /** Weak intern the string. */
  public static String intern(String string) {
    return INTERNER.intern(string);
  }
}

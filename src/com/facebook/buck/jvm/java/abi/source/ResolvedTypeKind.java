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

package com.facebook.buck.jvm.java.abi.source;

enum ResolvedTypeKind {
  /** Type would resolve successfully. */
  RESOLVED_TYPE,
  /** Type would resolve as an {@link javax.lang.model.type.ErrorType}. */
  ERROR_TYPE,
  /** The compiler would crash trying to resolve the type. */
  CRASH;

  public ResolvedTypeKind merge(ResolvedTypeKind other) {
    return (this.compareTo(other) >= 0) ? this : other;
  }
}

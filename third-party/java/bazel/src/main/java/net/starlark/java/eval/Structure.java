// Copyright 2014 The Bazel Authors. All rights reserved.
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

package net.starlark.java.eval;

import com.google.common.collect.ImmutableCollection;
import javax.annotation.Nullable;

/**
 * An interface for Starlark values (such as Bazel structs) with fields that may be accessed using
 * Starlark's {@code x.field} notation and optionally updating using an {@code x.f=y} assignment.
 */
public abstract class Structure extends StarlarkValue {

  /**
   * Returns the value of the field with the given name, or null if the field does not exist. The
   * interpreter (Starlark code) calls the getValue below, which has access to StarlarkSemantics.
   *
   * <p>The set of names for which {@code getValue} returns non-null should match {@code
   * getFieldNames} if possible.
   *
   * <p>Note current interpreter/optimizer relies on the fact that this operation
   * produces the same result for given structure/field pair during the structure lifetime.
   *
   * @throws EvalException if a user-visible error occurs (other than non-existent field).
   */
  @Nullable
  public abstract Object getField(String name) throws EvalException;

  /**
   * Returns the names of this value's fields, in some undefined but stable order.
   *
   * <p>A call to {@code getValue} for each of these names should return non-null, though this is
   * not enforced.
   *
   * <p>The Starlark expression {@code dir(x)} reports the union of {@code getFieldNames()} and any
   * StarlarkMethod-annotated fields and methods of this value.
   */
  public abstract ImmutableCollection<String> getFieldNames();

  /**
   * Returns the error message to print for an attempt to access an undefined field.
   *
   * <p>May return null to use a default error message.
   */
  @Nullable
  public abstract String getErrorMessageForUnknownField(String field);

  /**
   * Updates the named field of this value as if by the Starlark statement {@code this.field =
   * value}.
   *
   * @throws EvalException if the update failed because this value is immutable, does not support
   *     field update, or update of that particular field, or because the value was inappropriate.
   */
  public final void setField(String field, Object value) throws EvalException {
    throw Starlark.errorf("%s value does not support field assignment", Starlark.type(this));
  }
}

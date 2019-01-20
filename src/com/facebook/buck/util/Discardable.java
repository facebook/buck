/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util;

import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Discardable is a simple class that just holds some instance of a type and has a discard() that
 * disables access to the instance. This can be used basically the same as having a @Nullable field,
 * but it's a little bit more friendly w/ infer and it's only one-way (i.e. the field only goes from
 * set to null).
 */
public class Discardable<T> {
  @Nullable private T value;

  public Discardable(T value) {
    this.value = value;
  }

  public void discard() {
    value = null;
  }

  public T get() {
    Objects.requireNonNull(value, "Discardabled accessed after discard().");
    return value;
  }
}

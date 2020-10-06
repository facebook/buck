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

package com.facebook.buck.step;

import java.util.Optional;

/** Auxiliary class to pass result of arbitrary type between steps. */
public class BuildStepResultHolder<T> {

  private Optional<T> value = Optional.empty();

  public Optional<T> getValue() {
    return value;
  }

  public void setValue(T value) {
    this.value = Optional.of(value);
  }
}

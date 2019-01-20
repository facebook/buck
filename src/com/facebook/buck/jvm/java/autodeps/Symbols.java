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

package com.facebook.buck.jvm.java.autodeps;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;

/** Lightweight class that is used to pass some collections around, avoiding unnecessary copying. */
@JsonSerialize
final class Symbols {
  static final Symbols EMPTY = new Symbols();

  @JsonSerialize @JsonDeserialize final Iterable<String> provided;

  // For JsonDeserialize.
  Symbols() {
    this.provided = ImmutableList.of();
  }

  Symbols(Iterable<String> provided) {
    this.provided = provided;
  }
}

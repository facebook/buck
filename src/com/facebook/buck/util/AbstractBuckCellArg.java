/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.util.immutables.BuckStyleTuple;
import java.util.Optional;
import org.immutables.value.Value;

/** Helps parse common command line argument formats */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractBuckCellArg {

  public abstract Optional<String> getCellName();

  public abstract String getArg();

  /** Convenience constructor for an {@link AbstractBuckCellArg} */
  public static BuckCellArg of(String input) {
    int index = input.indexOf("//");
    if (index < 0) {
      return BuckCellArg.of(Optional.empty(), input);
    } else if (index == 0) {
      return BuckCellArg.of(Optional.empty(), input.substring(2));
    }
    return BuckCellArg.of(Optional.of(input.substring(0, index)), input.substring(index + 2));
  }
}

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

package com.facebook.buck.support.cli.args;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Optional;
import org.immutables.value.Value;

/** Helps parse common command line argument formats */
@BuckStyleValue
public abstract class BuckCellArg {

  public abstract Optional<String> getCellName();

  public abstract String getArg();

  @Value.Derived
  public String getBasePath() {
    return "//" + getArg();
  }

  /** Convenience constructor for an {@link BuckCellArg} */
  public static BuckCellArg of(String input) {
    int index = input.indexOf("//");
    if (index < 0) {
      return of(Optional.empty(), input);
    } else if (index == 0) {
      return of(Optional.empty(), input.substring(2));
    }
    return of(Optional.of(input.substring(0, index)), input.substring(index + 2));
  }

  public static BuckCellArg of(Optional<String> cellName, String arg) {
    return ImmutableBuckCellArg.of(cellName, arg);
  }
}

/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.google.common.collect.ImmutableList;

public class Linkers {

  private Linkers() {}

  /**
   * Prefixes each of the given linker arguments with "-Xlinker" so that the compiler linker
   * driver will pass these arguments directly down to the linker rather than interpreting them
   * itself.
   *
   * e.g. ["-rpath", "hello/world"] -&gt; ["-Xlinker", "-rpath", "-Xlinker", "hello/world"]
   *
   * Arguments that do not contain commas can instead be passed using the shorter
   * "-Wl,ARGUMENT" form.
   *
   * e.g., ["-rpath", "hello/world"] -&gt; ["-Wl,-rpath", "-Wl,hello/world" ]
   *
   * @param args arguments for the linker.
   * @return arguments to be passed to the compiler linker driver.
   */
  public static Iterable<String> iXlinker(Iterable<String> args) {
    ImmutableList.Builder<String> escaped = ImmutableList.builder();
    for (String arg : args) {
      if (arg.contains(",")) {
        escaped.add("-Xlinker");
        escaped.add(arg);
      } else {
        escaped.add("-Wl," + arg);
      }
    }

    return escaped.build();
  }

  public static Iterable<String> iXlinker(String... args) {
    return iXlinker(ImmutableList.copyOf(args));
  }

}

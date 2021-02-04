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

package com.facebook.buck.apple;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;

public class AppleStripFlags {
  public static ImmutableList<Arg> getStripArgs(StripStyle stripStyle, boolean stripSwift) {
    if (stripSwift) {
      switch (stripStyle) {
        case DEBUGGING_SYMBOLS:
          return ImmutableList.of(StringArg.of("-S"));
        case NON_GLOBAL_SYMBOLS:
          return ImmutableList.of(StringArg.of("-x"), StringArg.of("-T"));
        case ALL_SYMBOLS:
          // We can't just use -T here as this changes the default behavior. From the man page:
          //   When strip is used with no options on an executable file, it checks that file to see
          //   if it uses the dynamic link editor. If it does, the effect of the strip command is
          //   the same as using the -u and -r options.
          return ImmutableList.of(StringArg.of("-r"), StringArg.of("-u"), StringArg.of("-T"));
      }
    } else {
      switch (stripStyle) {
        case DEBUGGING_SYMBOLS:
          return ImmutableList.of(StringArg.of("-S"));
        case NON_GLOBAL_SYMBOLS:
          return ImmutableList.of(StringArg.of("-x"));
        case ALL_SYMBOLS:
          return ImmutableList.of();
      }
    }
    throw new HumanReadableException("unknown strip style %s", stripStyle.toString());
  }
}

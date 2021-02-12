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

package com.facebook.buck.io.namedpipes.windows;

import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandle;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Arrays;

/** An internal exception thrown from windows named pipe implementation. */
class WindowsNamedPipeException extends IOException {

  public WindowsNamedPipeException(String messageFormat, Object... args) {
    super(appendErrorMessage(messageFormat, args));
  }

  private static String appendErrorMessage(String messageFormat, Object... args) {
    ImmutableList<Object> argsList =
        ImmutableList.builderWithExpectedSize(args.length + 1)
            .addAll(Arrays.asList(args))
            .add(WindowsHandle.getNumberOfOpenedHandles())
            .build();
    return String.format(messageFormat + " Opened handles count: %s", argsList.toArray());
  }
}

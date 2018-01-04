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

package com.facebook.buck.toolchain;

import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.HumanReadableException;
import javax.annotation.Nullable;

/** An exception that indicates a problem during toolchain creation. */
public class ToolchainInstantiationException extends HumanReadableException {

  public ToolchainInstantiationException(String humanReadableFormatString, Object... args) {
    super(humanReadableFormatString, args);
  }

  public ToolchainInstantiationException(String humanReadableErrorMessage) {
    super(humanReadableErrorMessage);
  }

  public ToolchainInstantiationException(
      @Nullable Throwable cause, String humanReadableErrorMessage) {
    super(cause, humanReadableErrorMessage);
  }

  public ToolchainInstantiationException(
      @Nullable Throwable cause, String humanReadableFormatString, Object... args) {
    super(cause, humanReadableFormatString, args);
  }

  public ToolchainInstantiationException(ExceptionWithHumanReadableMessage e) {
    super(e);
  }

  public static ToolchainInstantiationException wrap(HumanReadableException e) {
    return new ToolchainInstantiationException(e, e.getHumanReadableErrorMessage());
  }
}

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

package com.facebook.buck.core.rules.analysis;

import com.facebook.buck.core.exceptions.HumanReadableException;
import javax.annotation.Nullable;

/**
 * Represents the failure of an analysis phase implementation function
 *
 * <p>This exception should result in a {@link com.facebook.buck.util.ExitCode.BUILD_ERROR}
 */
public class RuleAnalysisException extends HumanReadableException {

  public RuleAnalysisException(
      @Nullable Throwable cause, String humanReadableFormatString, Object... args) {
    super(cause, humanReadableFormatString, args);
  }

  public RuleAnalysisException(@Nullable Throwable cause, String message) {
    super(cause, message);
  }
}

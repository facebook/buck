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

package com.facebook.buck.parser;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;

public class ParserMessages {
  protected ParserMessages() {}

  private static HumanReadableException createReadableGenericExceptionWithWhenSuffix(
      BuildTarget buildTarget, BuildTarget parseDep, Exception exceptionInput) {
    return new HumanReadableException(
        exceptionInput,
        "%s\n\nThis error happened while trying to get dependency '%s' of target '%s'",
        exceptionInput.getMessage(),
        parseDep,
        buildTarget);
  }

  public static HumanReadableException createReadableExceptionWithWhenSuffix(
      BuildTarget buildTarget, BuildTarget parseDep, BuildFileParseException exceptionInput) {
    return createReadableGenericExceptionWithWhenSuffix(buildTarget, parseDep, exceptionInput);
  }

  public static HumanReadableException createReadableExceptionWithWhenSuffix(
      BuildTarget buildTarget, BuildTarget parseDep, BuildTargetException exceptionInput) {
    return createReadableGenericExceptionWithWhenSuffix(buildTarget, parseDep, exceptionInput);
  }

  public static HumanReadableException createReadableExceptionWithWhenSuffix(
      BuildTarget buildTarget, BuildTarget parseDep, HumanReadableException exceptionInput) {
    return createReadableGenericExceptionWithWhenSuffix(buildTarget, parseDep, exceptionInput);
  }
}

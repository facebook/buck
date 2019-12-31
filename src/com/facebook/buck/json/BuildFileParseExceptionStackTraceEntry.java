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

package com.facebook.buck.json;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.nio.file.Path;

@BuckStyleValue
public interface BuildFileParseExceptionStackTraceEntry {
  Path getFileName();

  Number getLineNumber();

  String getFunctionName();

  String getText();

  static BuildFileParseExceptionStackTraceEntry of(
      Path fileName, Number lineNumber, String functionName, String text) {
    return ImmutableBuildFileParseExceptionStackTraceEntry.of(
        fileName, lineNumber, functionName, text);
  }
}

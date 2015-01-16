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

package com.facebook.buck.java;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Escaper;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.Set;

public interface Javac {

  /**
   * An escaper for arguments written to @argfiles.
   */
  Function<String, String> ARGFILES_ESCAPER =
      Escaper.escaper(
          Escaper.Quoter.DOUBLE,
          CharMatcher.anyOf("#\"'").or(CharMatcher.WHITESPACE));

  int buildWithClasspath(
      ExecutionContext context,
      ImmutableList<String> options,
      Set<Path> buildClasspathEntries) throws InterruptedException;

  String getDescription(ExecutionContext context, ImmutableList<String> options);

  String getShortName();
}

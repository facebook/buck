/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.swift;

import com.facebook.buck.config.BuckConfig;
import com.google.common.base.Splitter;
import java.util.Optional;

/** A Swift-specific "view" of BuckConfig. */
public class SwiftBuckConfig {
  private static final String SECTION_NAME = "swift";
  public static final String COMPILER_FLAGS_NAME = "compiler_flags";
  public static final String VERSION_NAME = "version";

  private final BuckConfig delegate;

  public SwiftBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  private Optional<Iterable<String>> getFlags(String field) {
    Optional<String> value = delegate.getValue(SECTION_NAME, field);
    return value.map(input -> Splitter.on(" ").split(input.trim()));
  }

  public Optional<Iterable<String>> getCompilerFlags() {
    return getFlags(COMPILER_FLAGS_NAME);
  }

  public Optional<String> getVersion() {
    return delegate.getValue(SECTION_NAME, VERSION_NAME);
  }
}

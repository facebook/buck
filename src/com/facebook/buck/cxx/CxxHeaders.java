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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.Map;

@Value.Immutable
@BuckStyleImmutable
public interface CxxHeaders {

  /**
   * Maps the name of the header (e.g. the path used to include it in a C/C++ source) to the
   * real location of the header.
   */
  Map<Path, SourcePath> getNameToPathMap();

  /**
   * Maps the full of the header (e.g. the path to the header that appears in error messages) to
   * the real location of the header.
   */
  Map<Path, SourcePath> getFullNameToPathMap();

}

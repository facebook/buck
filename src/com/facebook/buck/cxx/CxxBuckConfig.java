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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.model.BuildTarget;

/**
 * Contains platform independent settings for C/C++ rules.
 */
public class CxxBuckConfig {

  private final BuckConfig delegate;

  public CxxBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * @return the {@link BuildTarget} which represents the lex library.
   */
  public BuildTarget getLexDep() {
    return delegate.getRequiredBuildTarget("cxx", "lex_dep");
  }

  /**
   * @return the {@link BuildTarget} which represents the python library.
   */
  public BuildTarget getPythonDep() {
    return delegate.getRequiredBuildTarget("cxx", "python_dep");
  }

}

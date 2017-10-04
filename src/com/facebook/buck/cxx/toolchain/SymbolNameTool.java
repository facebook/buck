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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;

/**
 * Provides methods to extract symbol names from native formats (e.g. binaries, shared libraries,
 * object files).
 */
public interface SymbolNameTool {

  /**
   * Creates a {@link BuildRule} which extracts all undefined symbols from the given inputs.
   *
   * @param target the name to use when creating the rule which extracts the symbols.
   * @return a {@link SourcePath} referring to a file containing all undefined symbols, one per
   *     line, in the given inputs.
   */
  SourcePath createUndefinedSymbolsFile(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      Iterable<? extends SourcePath> linkerInputs);
}

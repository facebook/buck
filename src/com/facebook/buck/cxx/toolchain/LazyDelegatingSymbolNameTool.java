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

import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.MoreSuppliers;
import java.util.function.Supplier;

public class LazyDelegatingSymbolNameTool implements SymbolNameTool {
  private Supplier<SymbolNameTool> delegate;

  public LazyDelegatingSymbolNameTool(Supplier<SymbolNameTool> delegate) {
    this.delegate = MoreSuppliers.memoize(delegate::get);
  }

  @Override
  public SourcePath createUndefinedSymbolsFile(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      Iterable<? extends SourcePath> linkerInputs) {
    return delegate
        .get()
        .createUndefinedSymbolsFile(
            projectFilesystem, baseParams, graphBuilder, ruleFinder, target, linkerInputs);
  }
}

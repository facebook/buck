/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.intellij.ideabuck.macro;

import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.intellij.openapi.actionSystem.DataContext;
import org.jetbrains.annotations.Nullable;

/** Macro that expands to the fully-qualified target pattern, e.g., "foo//bar/baz:qux/file.txt" */
public class BuckTargetPatternMacro extends AbstractBuckTargetPatternMacro {

  @Override
  public String getName() {
    return "BuckTargetPattern";
  }

  @Override
  public String getDescription() {
    return "Full target pattern for the selected file";
  }

  @Nullable
  @Override
  public String expand(DataContext dataContext) {
    return expandToTargetPattern(dataContext).map(BuckTargetPattern::toString).orElse(null);
  }
}

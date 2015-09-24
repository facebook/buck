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

package com.facebook.buck.go;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Preconditions;
import com.google.common.io.Files;

import java.nio.file.Path;
import java.nio.file.Paths;

abstract class GoLinkable extends AbstractBuildRule {

  GoLinkable(
      BuildRuleParams params,
      SourcePathResolver resolver) {
    super(params, resolver);
  }

  /**
   * @return the path in the symlink tree as used by the compiler. This is usually the package
   *         name + '.a'.
   */
  public Path getPathInSymlinkTree() {
    Path ruleOutput = getPathToOutput();
    Preconditions.checkNotNull(ruleOutput);

    String extension = Files.getFileExtension(ruleOutput.toString());
    Path symlinkPath = Paths.get(getGoPackageName().toString() + (extension.equals(
            "") ? "" : "." + extension));
    return symlinkPath;
  }

  abstract Path getGoPackageName();
}

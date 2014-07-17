/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

/**
 * {@link SourcePath} similar to {@link PathSourcePath} that is not matched by
 * {@link SourcePaths#filterInputsToCompareToOutput(Iterable)}.
 * <p>
 * This is designed to be used when the contents of the file at the {@link Path} should not
 * contribute to a {@link RuleKey}.
 */
public class InternalSourcePath extends AbstractSourcePath {

  private final Path relativePath;

  public InternalSourcePath(Path relativePath) {
    Preconditions.checkNotNull(relativePath);
    Preconditions.checkArgument(relativePath.startsWith(BuckConstant.BUCK_OUTPUT_PATH));
    this.relativePath = relativePath;
  }

  @Override
  public Path resolve() {
    return relativePath;
  }

  @Override
  public Path asReference() {
    return relativePath;
  }

}

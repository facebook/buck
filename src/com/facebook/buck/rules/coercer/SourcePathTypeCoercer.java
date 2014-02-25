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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

public class SourcePathTypeCoercer extends LeafTypeCoercer<SourcePath> {
  private final TypeCoercer<BuildTarget> buildTargetTypeCoercer;
  private final TypeCoercer<Path> pathTypeCoercer;

  SourcePathTypeCoercer(
      TypeCoercer<BuildTarget> buildTargetTypeCoercer,
      TypeCoercer<Path> pathTypeCoercer) {
    this.buildTargetTypeCoercer = Preconditions.checkNotNull(buildTargetTypeCoercer);
    this.pathTypeCoercer = Preconditions.checkNotNull(pathTypeCoercer);
  }

  @Override
  public Class<SourcePath> getOutputClass() {
    return SourcePath.class;
  }

  @Override
  public SourcePath coerce(
      BuildRuleResolver buildRuleResolver, Path pathRelativeToProjectRoot, Object object)
      throws CoerceFailedException {
    try {
      BuildTarget buildTarget =
          buildTargetTypeCoercer.coerce(buildRuleResolver, pathRelativeToProjectRoot, object);
      return new BuildTargetSourcePath(buildTarget);
    } catch (CoerceFailedException e) {
      try {
        Path path = pathTypeCoercer.coerce(buildRuleResolver, pathRelativeToProjectRoot, object);
        return new FileSourcePath(path.toString());
      } catch (CoerceFailedException e1) {
        throw CoerceFailedException.simple(pathRelativeToProjectRoot, object, getOutputClass());
      }
    }
  }
}

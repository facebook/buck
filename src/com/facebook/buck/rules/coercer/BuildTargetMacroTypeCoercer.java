/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.BuildTargetMacro;
import com.google.common.collect.ImmutableList;
import java.util.function.Function;

/** Coercer for macros which take a single {@link BuildTarget} arg. */
public final class BuildTargetMacroTypeCoercer<M extends BuildTargetMacro>
    implements MacroTypeCoercer<M> {

  /** Should target be resolved for host platform or target */
  public enum TargetOrHost {
    TARGET,
    HOST,
  }

  private final TypeCoercer<BuildTarget> buildTargetTypeCoercer;
  private final Class<M> mClass;
  private final TargetOrHost targetOrHost;
  private final Function<BuildTarget, M> factory;

  public BuildTargetMacroTypeCoercer(
      TypeCoercer<BuildTarget> buildTargetTypeCoercer,
      Class<M> mClass,
      TargetOrHost targetOrHost,
      Function<BuildTarget, M> factory) {
    this.buildTargetTypeCoercer = buildTargetTypeCoercer;
    this.mClass = mClass;
    this.targetOrHost = targetOrHost;
    this.factory = factory;
  }

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    return buildTargetTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, M macro, TypeCoercer.Traversal traversal) {
    buildTargetTypeCoercer.traverse(cellRoots, macro.getTarget(), traversal);
  }

  @Override
  public Class<M> getOutputClass() {
    return mClass;
  }

  @Override
  public M coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      ImmutableList<String> args)
      throws CoerceFailedException {
    if (args.size() != 1) {
      throw new CoerceFailedException(
          String.format("expected exactly one argument (found %d)", args.size()));
    }
    BuildTarget target =
        buildTargetTypeCoercer.coerce(
            cellRoots,
            filesystem,
            pathRelativeToProjectRoot,
            targetOrHost == TargetOrHost.TARGET ? targetConfiguration : hostConfiguration,
            hostConfiguration,
            args.get(0));
    return factory.apply(target);
  }
}

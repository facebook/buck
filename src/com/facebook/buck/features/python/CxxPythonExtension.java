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

package com.facebook.buck.features.python;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import java.nio.file.Path;

public abstract class CxxPythonExtension extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements PythonPackagable, HasRuntimeDeps {

  public CxxPythonExtension(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem, BuildRuleParams params) {
    super(buildTarget, projectFilesystem, params);
  }

  @VisibleForTesting
  protected abstract BuildRule getExtension(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  public abstract Path getModule();

  @Override
  public abstract PythonPackageComponents getPythonPackageComponents(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  public abstract NativeLinkTarget getNativeLinkTarget(PythonPlatform pythonPlatform);
}

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

package com.facebook.buck.python;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.SharedNativeLinkTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;

import java.nio.file.Path;

public abstract class CxxPythonExtension extends NoopBuildRule implements PythonPackagable {

  public CxxPythonExtension(
      BuildRuleParams params,
      SourcePathResolver resolver) {
    super(params, resolver);
  }

  @VisibleForTesting
  protected abstract BuildRule getExtension(
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException;

  public abstract Path getModule();

  @Override
  public abstract PythonPackageComponents getPythonPackageComponents(
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException;

  public abstract SharedNativeLinkTarget getNativeLinkTarget(PythonPlatform pythonPlatform);

  public static Function<CxxPythonExtension, SharedNativeLinkTarget> getNativeLinkTargetFn(
      final PythonPlatform pythonPlatform) {
    return new Function<CxxPythonExtension, SharedNativeLinkTarget>() {
      @Override
      public SharedNativeLinkTarget apply(CxxPythonExtension cxxPythonExtension) {
        return cxxPythonExtension.getNativeLinkTarget(pythonPlatform);
      }
    };
  }

}

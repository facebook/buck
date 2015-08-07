/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.annotations.VisibleForTesting;

public abstract class PythonBinary extends AbstractBuildRule implements BinaryBuildRule {

  private final String mainModule;
  private final PythonPackageComponents components;

  public PythonBinary(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      String mainModule,
      PythonPackageComponents components) {
    super(buildRuleParams, resolver);
    this.mainModule = mainModule;
    this.components = components;
  }

  @VisibleForTesting
  protected String getMainModule() {
    return mainModule;
  }

  @VisibleForTesting
  protected PythonPackageComponents getComponents() {
    return components;
  }

}

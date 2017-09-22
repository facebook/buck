/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;

/**
 * This class should not ever be extended.
 *
 * <p>Everywhere that currently extends it is a legacy caller of SourcePathResolver which should be
 * migrated to extend AbstractBuildRuleWithDeclaredAndExtraDeps directly and use SourcePathResolver
 * only from the BuildContext supplied in getBuildSteps.
 */
public abstract class AbstractBuildRuleWithResolver
    extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private final SourcePathResolver resolver;

  protected AbstractBuildRuleWithResolver(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.resolver = resolver;
  }

  public final SourcePathResolver getResolver() {
    return resolver;
  }
}

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

package com.facebook.buck.rules;

import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.ProcessExecutor;
import java.io.IOException;

/**
 * Contain items used to construct a {@link KnownBuildRuleTypes} that are shared between all {@link
 * Cell} instances.
 */
public class KnownBuildRuleTypesFactory {

  private final ProcessExecutor executor;
  private final AndroidDirectoryResolver directoryResolver;

  public KnownBuildRuleTypesFactory(
      ProcessExecutor executor, AndroidDirectoryResolver directoryResolver) {
    this.executor = executor;
    this.directoryResolver = directoryResolver;
  }

  public KnownBuildRuleTypes create(BuckConfig config, ProjectFilesystem filesystem)
      throws IOException, InterruptedException {
    return KnownBuildRuleTypes.createInstance(config, filesystem, executor, directoryResolver);
  }
}

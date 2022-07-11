/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.cd.params;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

/**
 * Compiler Daemon params. Used to pass into daemon worker tool step. Doesn't implement {@link
 * AddsToRuleKey} interface.
 */
@BuckStyleValue
public abstract class CDParams {

  abstract RulesCDParams getRulesCDParams();

  @Value.Derived
  public boolean isEnabled() {
    return getRulesCDParams().isEnabled();
  }

  @Value.Derived
  public ImmutableList<String> getStartCommandOptions() {
    return getRulesCDParams().getStartCommandOptions();
  }

  @Value.Derived
  public int getWorkerToolPoolSize() {
    return getRulesCDParams().getWorkerToolPoolSize();
  }

  @Value.Derived
  public int getWorkerToolMaxInstancesSize() {
    return getRulesCDParams().getWorkerToolMaxInstancesSize();
  }

  @Value.Derived
  public int getBorrowFromPoolTimeoutInSeconds() {
    return getRulesCDParams().getBorrowFromPoolTimeoutInSeconds();
  }

  @Value.Derived
  public int getMaxWaitForResultTimeoutInSeconds() {
    return getRulesCDParams().getMaxWaitForResultTimeoutInSeconds();
  }

  @Value.Derived
  public boolean isIncludeAllBucksEnvVariables() {
    return getRulesCDParams().isIncludeAllBucksEnvVariables();
  }

  public abstract RelPath getLogDirectory();

  /** Creates {@link CDParams} */
  public static CDParams of(RulesCDParams rulesCDParams, ProjectFilesystem projectFilesystem) {
    return ImmutableCDParams.ofImpl(rulesCDParams, projectFilesystem.getBuckPaths().getLogDir());
  }
}

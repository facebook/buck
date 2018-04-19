/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_client;

import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.MinionRequirements;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.rules.ParallelRuleKeyCalculator;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import org.immutables.value.Value;

/** Encapsulates arguments needed to invoke DistBuildController */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractDistBuildControllerInvocationArgs {
  abstract ListeningExecutorService getExecutorService();

  abstract ProjectFilesystem getProjectFilesystem();

  abstract FileHashCache getFileHashCache();

  abstract InvocationInfo getInvocationInfo();

  abstract BuildMode getBuildMode();

  abstract MinionRequirements getMinionRequirements();

  abstract String getRepository();

  abstract String getTenantId();

  abstract ListenableFuture<ParallelRuleKeyCalculator<RuleKey>> getRuleKeyCalculatorFuture();
}

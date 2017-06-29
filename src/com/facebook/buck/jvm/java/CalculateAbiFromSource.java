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

package com.facebook.buck.jvm.java;

import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;

public class CalculateAbiFromSource extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements CalculateAbi, InitializableFromDisk<Object>, SupportsInputBasedRuleKey {

  @AddToRuleKey private final JavaAbiAndLibraryWorker worker;

  public CalculateAbiFromSource(BuildRuleParams params, JavaAbiAndLibraryWorker worker) {
    super(params);

    this.worker = worker;
  }

  JavaAbiAndLibraryWorker getWorker() {
    return worker;
  }

  @Override
  public ListenableFuture<Void> buildLocally(
      BuildContext buildContext,
      BuildableContext buildableContext,
      ExecutionContext executionContext,
      StepRunner stepRunner,
      ListeningExecutorService service) {
    return worker
        .getAbiOutputs()
        .buildLocally(buildContext, buildableContext, executionContext, stepRunner, service);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    throw new AssertionError("Should never get here.");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return Preconditions.checkNotNull(worker.getAbiOutputs().getSourcePathToOutput());
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJarContents() {
    return worker.getAbiOutputs().getJarContents();
  }

  @Override
  public Object initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    // Warm up the jar contents. We just wrote the thing, so it should be in the filesystem cache
    worker.getAbiOutputs().initializeFromDisk();
    return new Object();
  }

  @Override
  public BuildOutputInitializer<Object> getBuildOutputInitializer() {
    return new BuildOutputInitializer<>(getBuildTarget(), this);
  }
}

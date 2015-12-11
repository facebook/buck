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
package com.facebook.buck.apple;

import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasPostBuildSteps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;

public class AppleDsym extends AbstractBuildRule implements HasPostBuildSteps {

  private static final Logger LOG = Logger.get(AppleDsym.class);

  @AddToRuleKey
  private final Tool lldb;

  @AddToRuleKey
  private final Tool dsymutil;

  @AddToRuleKey
  private final Tool strip;

  private final Path bundleRoot;
  private final Path bundleBinaryPath;
  private final Path dsymPath;

  public AppleDsym(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Path bundleRoot,
      Path bundleBinaryPath,
      Tool dsymutil,
      Tool lldb,
      Tool strip) {
    super(buildRuleParams, resolver);

    this.dsymutil = dsymutil;
    this.lldb = lldb;
    this.strip = strip;

    this.bundleRoot = bundleRoot;
    this.bundleBinaryPath = bundleBinaryPath;
    this.dsymPath = bundleRoot
        .getParent()
        .resolve(bundleRoot.getFileName().toString() + ".dSYM");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(dsymPath);

    DsymStep generateDsymStep = new DsymStep(
        getProjectFilesystem(),
        dsymutil.getEnvironment(getResolver()),
        dsymutil.getCommandPrefix(getResolver()),
        bundleBinaryPath,
        dsymPath);
    Step stripDebugSymbolsStep = getStripDebugSymbolsStep();

    return ImmutableList.of(generateDsymStep, stripDebugSymbolsStep);
  }

  private Step getStripDebugSymbolsStep() {
    return new Step() {
      @Override
      public int execute(ExecutionContext context) throws IOException, InterruptedException {
        // Don't strip binaries which are already code-signed.  Note: we need to use
        // binaryOutputPath instead of bundleBinaryPath because codesign will evaluate the
        // entire .app bundle, even if you pass it the direct path to the embedded binary.
        if (!CodeSigning.hasValidSignature(context.getProcessExecutor(), bundleBinaryPath)) {
          return (new DefaultShellStep(
              getProjectFilesystem().getRootPath(),
              ImmutableList.<String>builder()
                  .addAll(strip.getCommandPrefix(getResolver()))
                  .add("-S")
                  .add(getProjectFilesystem().resolve(bundleBinaryPath).toString())
                  .build(),
              strip.getEnvironment(getResolver())))
              .execute(context);
        } else {
          LOG.info("Not stripping code-signed binary.");
          return 0;
        }
      }

      @Override
      public String getShortName() {
        return "strip binary";
      }

      @Override
      public String getDescription(ExecutionContext context) {
        return String.format(
            "strip debug symbols from binary '%s'",
            bundleRoot);
      }
    };
  }

  @Override
  public Path getPathToOutput() {
    return dsymPath;
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.<Step>of(
        new Step() {
          @Override
          public int execute(ExecutionContext context) throws IOException, InterruptedException {
            ImmutableList<String> lldbCommandPrefix = lldb.getCommandPrefix(getResolver());
            ProcessExecutorParams params = ProcessExecutorParams
                .builder()
                .addCommand(lldbCommandPrefix.toArray(new String[lldbCommandPrefix.size()]))
                .build();
            return context.getProcessExecutor().launchAndExecute(
                params,
                ImmutableSet.<ProcessExecutor.Option>of(),
                Optional.of(
                    String.format("target create %s\ntarget symbols add %s", bundleRoot, dsymPath)),
                Optional.<Long>absent(),
                Optional.<Function<Process, Void>>absent()).getExitCode();
          }

          @Override
          public String getShortName() {
            return "register debug symbols";
          }

          @Override
          public String getDescription(ExecutionContext context) {
            return String.format(
                "register debug symbols for binary '%s': '%s'",
                bundleRoot,
                dsymPath);
          }
        });
  }
}

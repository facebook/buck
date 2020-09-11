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

package com.facebook.buck.android;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

public class InstrumentationStep extends IsolatedShellStep {

  private final ProjectFilesystem filesystem;
  private final ImmutableList<String> javaRuntimeLauncher;
  private final AndroidInstrumentationTestJVMArgs jvmArgs;
  private final Supplier<Path> classpathArgfile;

  private Optional<Long> testRuleTimeoutMs;

  public InstrumentationStep(
      ProjectFilesystem filesystem,
      RelPath cellPath,
      ImmutableList<String> javaRuntimeLauncher,
      AndroidInstrumentationTestJVMArgs jvmArgs,
      Optional<Long> testRuleTimeoutMs,
      boolean withDownwardApi) {
    super(filesystem.getRootPath(), cellPath, withDownwardApi);
    this.filesystem = filesystem;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.jvmArgs = jvmArgs;
    this.testRuleTimeoutMs = testRuleTimeoutMs;

    this.classpathArgfile =
        MoreSuppliers.memoize(
            () -> {
              try {
                return filesystem.createTempFile("classpath-argfile", "");
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws InterruptedException, IOException {
    ensureClasspathArgfile();
    return super.executeIsolatedStep(context);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(javaRuntimeLauncher);

    jvmArgs.formatCommandLineArgsToList(filesystem, args, classpathArgfile);

    if (jvmArgs.isDebugEnabled()) {
      warnUser(context, "Debugging. Suspending JVM. Connect android debugger to proceed.");
    }

    return args.build();
  }

  @Override
  public String getShortName() {
    return "instrumentation test";
  }

  @Override
  public Optional<Long> getTimeout() {
    return testRuleTimeoutMs;
  }

  /** Ensures the classpath argfile for Java 9+ invocations has been created. */
  public void ensureClasspathArgfile() throws IOException {
    if (jvmArgs.shouldUseClasspathArgfile()) {
      jvmArgs.writeClasspathArgfile(filesystem, classpathArgfile.get());
    }
  }

  private void warnUser(IsolatedExecutionContext context, String message) {
    context.getStdErr().println(context.getAnsi().asWarningText(message));
  }
}

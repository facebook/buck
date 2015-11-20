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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class JUnitStep extends ShellStep {
  private static final Logger LOG = Logger.get(JUnitStep.class);

  private final ProjectFilesystem filesystem;
  private final ImmutableMap<String, String> nativeLibsEnvironment;
  private final Optional<Long> testRuleTimeoutMs;
  private final JUnitJvmArgs junitJvmArgs;

  // Set when the junit command times out.
  private boolean hasTimedOut = false;

  public JUnitStep(
      ProjectFilesystem filesystem,
      Map<String, String> nativeLibsEnvironment,
      Optional<Long> testRuleTimeoutMs,
      JUnitJvmArgs junitJvmArgs) {
    super(filesystem.getRootPath());
    this.filesystem = filesystem;
    this.nativeLibsEnvironment = ImmutableMap.copyOf(nativeLibsEnvironment);
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.junitJvmArgs = junitJvmArgs;
  }

  @Override
  public String getShortName() {
    return "junit";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("java");

    junitJvmArgs.formatCommandLineArgsToList(
        args,
        filesystem,
        context.getVerbosity(),
        context.getDefaultTestTimeoutMillis());

    if (junitJvmArgs.isDebugEnabled()) {
      warnUser(context,
          "Debugging. Suspending JVM. Connect a JDWP debugger to port 5005 to proceed.");
    }

    return args.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    ImmutableMap.Builder<String, String> env = ImmutableMap.builder();
    if (junitJvmArgs.getTmpDirectory().isPresent()) {
      env.put("TMP", junitJvmArgs.getTmpDirectory().get().toString());
    }
    env.putAll(nativeLibsEnvironment);
    return env.build();
  }

  private void warnUser(ExecutionContext context, String message) {
    context.getStdErr().println(context.getAnsi().asWarningText(message));
  }

  @Override
  protected Optional<Long> getTimeout() {
    return testRuleTimeoutMs;
  }

  @Override
  protected Optional<Function<Process, Void>> getTimeoutHandler(final ExecutionContext context) {
    return Optional.<Function<Process, Void>>of(
        new Function<Process, Void>() {
          @Override
          public Void apply(Process process) {
            Optional<Long> pid = Optional.absent();
            try {
              switch(context.getPlatform()) {
                case LINUX:
                case MACOS: {
                  Field field = process.getClass().getDeclaredField("pid");
                  field.setAccessible(true);
                  try {
                    pid = Optional.of((long) field.getInt(process));
                  } catch (IllegalAccessException e) {
                    LOG.error(e, "Failed to access `pid`.");
                  }
                  break;
                }
                case WINDOWS: {
                  Field field = process.getClass().getDeclaredField("handle");
                  field.setAccessible(true);
                  try {
                    pid = Optional.of(field.getLong(process));
                  } catch (IllegalAccessException e) {
                    LOG.error(e, "Failed to access `handle`.");
                  }
                  break;
                }
                case UNKNOWN:
                  LOG.info("Unknown platform; unable to obtain the process id!");
                  break;
              }
            } catch (NoSuchFieldException e) {
              LOG.error(e);
            }

            Optional<Path> jstack = new ExecutableFinder(context.getPlatform())
                .getOptionalExecutable(Paths.get("jstack"), context.getEnvironment());
            if (!pid.isPresent() || !jstack.isPresent()) {
              LOG.info("Unable to print a stack trace for timed out test!");
              return null;
            }

            context.getStdErr().print(
                "Test has timed out!  Here is a trace of what it is currently doing:%n");
            try {
              context.getProcessExecutor().launchAndExecute(
                  /* command */ ProcessExecutorParams.builder()
                      .addCommand(jstack.get().toString(), "-l", pid.get().toString())
                      .setEnvironment(context.getEnvironment())
                      .build(),
                  /* options */ ImmutableSet.<ProcessExecutor.Option>builder()
                      .add(ProcessExecutor.Option.PRINT_STD_OUT)
                      .add(ProcessExecutor.Option.PRINT_STD_ERR)
                      .build(),
                  /* stdin */ Optional.<String>absent(),
                  /* timeOutMs */ Optional.of(TimeUnit.SECONDS.toMillis(30)),
                  /* timeOutHandler */ Optional.<Function<Process, Void>>of(
                      new Function<Process, Void>() {
                        @Override
                        public Void apply(Process input) {
                          context.getStdErr().print(
                              "Printing the stack took longer than 30 seconds. No longer trying.");
                          return null;
                        }
                      }
                  ));
            } catch (Exception e) {
              LOG.error(e);
            }
            return null;
          }
        });
  }

  @Override
  protected int getExitCodeFromResult(ExecutionContext context, ProcessExecutor.Result result) {
    int exitCode = result.getExitCode();

    // If we timed out, force the exit code to 0 just so that the step itself doesn't fail,
    // allowing us to interpret any test cases that finished before the bad test.  We signify
    // this special case by setting `hasTimedOut` which the result interpreter will query to
    // properly format its results.
    if (result.isTimedOut()) {
      exitCode = 0;
      hasTimedOut = true;
    }

    return exitCode;
  }

  public boolean hasTimedOut() {
    return hasTimedOut;
  }

}

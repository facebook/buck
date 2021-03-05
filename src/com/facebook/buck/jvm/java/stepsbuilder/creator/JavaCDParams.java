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

package com.facebook.buck.jvm.java.stepsbuilder.creator;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import java.util.function.Supplier;

/** Params related to javacd. Used to pass into javacd worker tool step. */
@BuckStyleValue
public abstract class JavaCDParams {

  public abstract boolean hasJavaCDEnabled();

  public abstract ImmutableList<String> getJavaRuntimeLauncherCommand();

  public abstract Supplier<RelPath> getJavacdBinaryPathSupplier();

  public abstract ImmutableList<String> getStartCommandOptions();

  public abstract int getWorkerToolPoolSize();

  public abstract int getBorrowFromPoolTimeoutInSeconds();

  /** Creates {@link JavaCDParams} */
  public static JavaCDParams of(
      boolean hasJavaCDEnabled,
      ImmutableList<String> javaRuntimeLauncherCommand,
      Supplier<RelPath> javacdBinaryPathSupplier,
      Iterable<String> startCommandOptions,
      int workerToolPoolSize,
      int borrowFromPoolTimeoutInSeconds) {
    return ImmutableJavaCDParams.ofImpl(
        hasJavaCDEnabled,
        javaRuntimeLauncherCommand,
        javacdBinaryPathSupplier,
        startCommandOptions,
        workerToolPoolSize,
        borrowFromPoolTimeoutInSeconds);
  }
}

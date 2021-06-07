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

package com.facebook.buck.jvm.java.stepsbuilder.params;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

/** Params related to javacd. Used to pass into javacd worker tool step. */
@BuckStyleValue
public abstract class JavaCDParams {

  abstract BaseJavaCDParams getBaseJavaCDParams();

  @Value.Derived
  public boolean hasJavaCDEnabled() {
    return getBaseJavaCDParams().hasJavaCDEnabled();
  }

  @Value.Derived
  public ImmutableList<String> getStartCommandOptions() {
    return getBaseJavaCDParams().getStartCommandOptions();
  }

  @Value.Derived
  public int getWorkerToolPoolSize() {
    return getBaseJavaCDParams().getWorkerToolPoolSize();
  }

  @Value.Derived
  public int getWorkerToolMaxInstancesSize() {
    return getBaseJavaCDParams().getWorkerToolMaxInstancesSize();
  }

  @Value.Derived
  public int getBorrowFromPoolTimeoutInSeconds() {
    return getBaseJavaCDParams().getBorrowFromPoolTimeoutInSeconds();
  }

  @Value.Derived
  public int getMaxWaitForResultTimeoutInSeconds() {
    return getBaseJavaCDParams().getMaxWaitForResultTimeoutInSeconds();
  }

  public abstract RelPath getLogDirectory();

  /** Creates {@link JavaCDParams} */
  public static JavaCDParams of(
      BaseJavaCDParams baseJavaCDParams, ProjectFilesystem projectFilesystem) {
    return ImmutableJavaCDParams.ofImpl(
        baseJavaCDParams, projectFilesystem.getBuckPaths().getLogDir());
  }
}

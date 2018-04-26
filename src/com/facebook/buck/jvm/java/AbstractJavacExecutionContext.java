/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableMap;
import java.io.PrintStream;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractJavacExecutionContext {
  public abstract JavacEventSink getEventSink();

  public abstract PrintStream getStdErr();

  public abstract ClassLoaderCache getClassLoaderCache();

  public abstract Verbosity getVerbosity();

  public abstract CellPathResolver getCellPathResolver();

  public abstract JavaPackageFinder getJavaPackageFinder();

  public abstract ProjectFilesystem getProjectFilesystem();

  public abstract ProjectFilesystemFactory getProjectFilesystemFactory();

  public abstract ImmutableMap<String, String> getEnvironment();

  public abstract ProcessExecutor getProcessExecutor();
}

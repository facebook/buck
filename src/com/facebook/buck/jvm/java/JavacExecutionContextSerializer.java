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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CellPathResolverSerializer;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorSerializer;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class JavacExecutionContextSerializer {

  private JavacExecutionContextSerializer() {}

  private static final String VERBOSITY = "verbosity";
  private static final String CELL_PATH_RESOLVER = "cell_path_resolver";
  private static final String JAVA_PACKAGE_FINDER = "java_package_finder";
  private static final String PROJECT_FILE_SYSTEM_ROOT = "project_file_system_root";
  private static final String CLASS_USAGE_FILE_WRITER = "class_usage_file_writer";
  private static final String ENVIRONMENT = "env";
  private static final String PROCESS_EXECUTOR = "process_executor";
  private static final String ABSOLUTE_PATHS_FOR_INPUTS = "absolute_paths_for_inputs";
  private static final String DIRECT_TO_JAR_SETTINGS = "direct_to_jar_settings";

  public static ImmutableMap<String, Object> serialize(JavacExecutionContext context) {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();

    builder.put(VERBOSITY, context.getVerbosity().toString());
    builder.put(
        CELL_PATH_RESOLVER, CellPathResolverSerializer.serialize(context.getCellPathResolver()));
    builder.put(
        JAVA_PACKAGE_FINDER, JavaPackageFinderSerializer.serialize(context.getJavaPackageFinder()));
    builder.put(PROJECT_FILE_SYSTEM_ROOT, context.getProjectFilesystem().getRootPath().toString());
    builder.put(
        CLASS_USAGE_FILE_WRITER,
        ClassUsageFileWriterSerializer.serialize(context.getUsedClassesFileWriter()));
    builder.put(ENVIRONMENT, context.getEnvironment());
    builder.put(
        PROCESS_EXECUTOR, ProcessExecutorSerializer.serialize(context.getProcessExecutor()));
    builder.put(
        ABSOLUTE_PATHS_FOR_INPUTS,
        ImmutableList.copyOf(
            context.getAbsolutePathsForInputs().stream().map(Path::toString).iterator()));
    if (context.getDirectToJarOutputSettings().isPresent()) {
      builder.put(
          DIRECT_TO_JAR_SETTINGS,
          DirectToJarOutputSettingsSerializer.serialize(
              context.getDirectToJarOutputSettings().get()));
    }

    return builder.build();
  }

  @SuppressWarnings("unchecked")
  public static JavacExecutionContext deserialize(
      Map<String, Object> data,
      JavacEventSink eventSink,
      PrintStream stdErr,
      ClassLoaderCache classLoaderCache,
      Console console)
      throws InterruptedException {

    Verbosity verbosity =
        Verbosity.valueOf((String) Preconditions.checkNotNull(data.get(VERBOSITY)));

    CellPathResolver cellPathResolver =
        CellPathResolverSerializer.deserialize(
            (Map<String, Object>) Preconditions.checkNotNull(data.get(CELL_PATH_RESOLVER)));

    JavaPackageFinder javaPackageFinder =
        JavaPackageFinderSerializer.deserialize(
            (Map<String, Object>) Preconditions.checkNotNull(data.get(JAVA_PACKAGE_FINDER)));

    ProjectFilesystem projectFilesystem =
        new ProjectFilesystem(
            Paths.get((String) Preconditions.checkNotNull(data.get(PROJECT_FILE_SYSTEM_ROOT))));

    ClassUsageFileWriter classUsageFileWriter =
        ClassUsageFileWriterSerializer.deserialize(
            (Map<String, Object>) Preconditions.checkNotNull(data.get(CLASS_USAGE_FILE_WRITER)));

    ProcessExecutor processExecutor =
        ProcessExecutorSerializer.deserialize(
            (Map<String, Object>) Preconditions.checkNotNull(data.get(PROCESS_EXECUTOR)), console);

    ImmutableList<Path> absolutePathsForInputs =
        ImmutableList.copyOf(
            ((List<String>) Preconditions.checkNotNull(data.get(ABSOLUTE_PATHS_FOR_INPUTS)))
                .stream()
                .map(s -> Paths.get(s))
                .iterator());

    Optional<DirectToJarOutputSettings> directToJarOutputSettings = Optional.empty();
    if (data.containsKey(DIRECT_TO_JAR_SETTINGS)) {
      directToJarOutputSettings =
          Optional.of(
              DirectToJarOutputSettingsSerializer.deserialize(
                  (Map<String, Object>)
                      Preconditions.checkNotNull(data.get(DIRECT_TO_JAR_SETTINGS))));
    }

    return JavacExecutionContext.of(
        eventSink,
        stdErr,
        classLoaderCache,
        verbosity,
        cellPathResolver,
        javaPackageFinder,
        projectFilesystem,
        classUsageFileWriter,
        (Map<String, String>)
            Preconditions.checkNotNull(
                data.get(ENVIRONMENT),
                "Missing environment when deserializing JavacExectionContext"),
        processExecutor,
        absolutePathsForInputs,
        directToJarOutputSettings);
  }
}

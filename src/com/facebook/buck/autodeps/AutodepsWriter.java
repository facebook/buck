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

package com.facebook.buck.autodeps;

import com.facebook.buck.model.BuildTarget;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.Uninterruptibles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

public class AutodepsWriter {

  // Apparently PrettyPrinter is not thread-safe, so we must use a ThreadLocal.
  private static final ThreadLocal<PrettyPrinter> PRETTY_PRINTER =
      new ThreadLocal<PrettyPrinter>() {
    @Override
    protected PrettyPrinter initialValue() {
      DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
      prettyPrinter.indentArraysWith(
          new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n"));
      return prettyPrinter;
    }
  };

  private static final String GENERATED_BUILD_FILE_SUFFIX = ".autodeps";

  // The first placeholder should be the SHA-1 of the second placeholder.
  // Note the second placeholder should contain a trailing newline.
  private static final String AUTODEPS_CONTENTS_FORMAT_STRING =
      "#@# GENERATED FILE: DO NOT MODIFY %s #@#\n%s";

  /** Utility class: do not instantiate. */
  private AutodepsWriter() {}

  /**
   * Writes the {@code .autodeps} files in parallel using the {@code commandThreadManager}.
   * @param depsForBuildFiles Abstraction that contains the data that needs to be written to the
   *     {@code .autodeps} files.
   * @param buildFileName In practice, this should be derived from
   *     {@link com.facebook.buck.rules.Cell#getBuildFileName()}
   * @param mapper To aid in JSON serialization.
   * @return the number of files that were written.
   */
  public static int write(
      DepsForBuildFiles depsForBuildFiles,
      String buildFileName,
      ObjectMapper mapper,
      ListeningExecutorService executorService,
      int numThreads) throws IOException, ExecutionException {
    Preconditions.checkArgument(numThreads > 0, "Must be at least one thread available");

    // We are going to divide the work into N groups, where N is the size of the thread pool.
    ImmutableList<DepsForBuildFiles.BuildFileWithDeps> buildFilesWithDeps =
        ImmutableList.copyOf(depsForBuildFiles);
    int numBuildFiles = buildFilesWithDeps.size();
    if (numBuildFiles == 0) {
      return 0;
    }
    int chunkSize = numBuildFiles / numThreads;
    int extraItems = numBuildFiles % numThreads;

    // Add the work to the executor. Note that instead of creating one future per build file, we
    // create one future per thread. This should reduce object allocation and context switching.
    List<ListenableFuture<Integer>> futures = new ArrayList<>();
    String autodepsFileName = buildFileName + GENERATED_BUILD_FILE_SUFFIX;
    for (int i = 0, endIndex = 0; i < numThreads; i++) {
      // Calculate how many items the thread should process.
      int numItemsToProcess = chunkSize;
      if (extraItems > 0) {
        numItemsToProcess++;
        extraItems--;
      }

      // Note that if buildFilesWithDeps.size() < numThreads, then this will be true for some
      // iterations of this loop.
      if (numItemsToProcess == 0) {
        break;
      }

      // Determine the subset of buildFilesWithDeps for the thread to process.
      int startIndex = endIndex;
      endIndex = startIndex + numItemsToProcess;
      ImmutableList<DepsForBuildFiles.BuildFileWithDeps> work =
          buildFilesWithDeps.subList(startIndex, endIndex);

      // Submit a job to the executor that will write .autodeps files, as appropriate. It will
      // return the number of .autodeps files it needed to write.
      ListenableFuture<Integer> future = executorService.submit(new AutodepsCallable(
          work,
          autodepsFileName,
          mapper
      ));
      futures.add(future);
    }

    // Sum up the total number of files written from each worker.
    int totalWritten = 0;
    ListenableFuture<List<Integer>> futuresList = Futures.allAsList(futures);
    for (int numWritten : Uninterruptibles.getUninterruptibly(futuresList)) {
      totalWritten += numWritten;
    }
    return totalWritten;
  }

  /** Computes and writes {@code .autodeps} files for build files, as necessary. */
  private static class AutodepsCallable implements Callable<Integer> {
    private final ImmutableList<DepsForBuildFiles.BuildFileWithDeps> buildFilesWithDeps;
    private final String autodepsFileName;
    private final ObjectMapper mapper;

    AutodepsCallable(
        ImmutableList<DepsForBuildFiles.BuildFileWithDeps> buildFilesWithDeps,
        String autodepsFileName,
        ObjectMapper mapper) {
      this.buildFilesWithDeps = buildFilesWithDeps;
      this.autodepsFileName = autodepsFileName;
      this.mapper = mapper;
    }

    @Override
    public Integer call() throws IOException {
      int numWritten = 0;
      for (DepsForBuildFiles.BuildFileWithDeps buildFileWithDeps : buildFilesWithDeps) {
        Path generatedFile = buildFileWithDeps.getCellPath()
            .resolve(buildFileWithDeps.getBasePath())
            .resolve(autodepsFileName);

        SortedMap<String, Object> deps = new TreeMap<>();
        for (DepsForBuildFiles.DepsForRule depsForRule : buildFileWithDeps) {
          Iterable<BuildTarget> depsAsBuildTargets = depsForRule;
          List<String> depsAsStrings = FluentIterable.from(depsAsBuildTargets)
              .transform(Functions.toStringFunction())
              .toList();
          deps.put(depsForRule.getShortName(), depsAsStrings);
        }

        if (writeSignedFile(deps, generatedFile, mapper)) {
          numWritten++;
        }
      }

      return numWritten;
    }
  }

  /**
   * Writes the file only if the contents are different to avoid creating noise for Watchman/buckd.
   * @param deps Keys must be sorted so the output is generated consistently.
   * @param generatedFile Where to write the generated output.
   * @param mapper To aid in JSON serialization.
   * @return whether the file was written
   */
  private static boolean writeSignedFile(
      SortedMap<String, Object> deps,
      Path generatedFile,
      ObjectMapper mapper) throws IOException {
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
         HashingOutputStream hashingOutputStream =
             new HashingOutputStream(Hashing.sha1(), bytes)) {
      ObjectWriter jsonWriter = mapper.writer(PRETTY_PRINTER.get());
      jsonWriter.writeValue(hashingOutputStream, deps);

      // Flush a trailing newline through the ByteArrayOutputStream so it is included in the
      // HashingOutputStream.
      bytes.write('\n');

      HashCode hash = hashingOutputStream.hash();
      String serializedJson = bytes.toString(Charsets.UTF_8.name());
      String contentsToWrite = String.format(AUTODEPS_CONTENTS_FORMAT_STRING, hash, serializedJson);

      // Do not write file unless the contents have changed. Writing the file will cause the daemon
      // to indiscriminately invalidate any cached build rules for the associated build file.
      if (generatedFile.toFile().isFile()) {
        String existingContents = com.google.common.io.Files.toString(
            generatedFile.toFile(),
            Charsets.UTF_8);
        if (contentsToWrite.equals(existingContents)) {
          return false;
        }
      }

      try (Writer writer = Files.newBufferedWriter(generatedFile, Charsets.UTF_8)) {
        writer.write(contentsToWrite);
      }
      return true;
    }
  }
}

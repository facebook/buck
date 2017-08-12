// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.benchmarks;

import static com.android.tools.r8.benchmarks.BenchmarkUtils.printRuntimeNanoseconds;

import com.android.tools.r8.ClassFileResourceProvider;
import com.android.tools.r8.CompilationException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.D8Output;
import com.android.tools.r8.Resource;
import com.android.tools.r8.Resource.Origin;
import com.android.tools.r8.Resource.PathOrigin;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.FileUtils;
import com.android.tools.r8.utils.OutputMode;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.ZipUtils;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public class FrameworkIncrementalDexingBenchmark {
  private static final int ITERATIONS = 100;
  private static final int API = 24;
  private static final Path JAR_DESUGARED =
      Paths.get("third_party", "framework", "framework_14082017_desugared.jar");
  private static final Path JAR_NOT_DESUGARED =
      Paths.get("third_party", "framework", "framework_14082017.jar");
  private static final Path LIB =
      Paths.get("third_party", "android_jar", "lib-v" + API, "android.jar");

  static class InMemoryClassPathProvider implements ClassFileResourceProvider {
    Origin origin;
    Map<String, byte[]> resources;

    InMemoryClassPathProvider(Path archive) throws IOException {
      origin = new PathOrigin(archive, Origin.root());
      ImmutableMap.Builder<String, byte[]> builder = ImmutableMap.builder();
      ZipUtils.iter(
          archive.toString(),
          (entry, stream) -> {
            String name = entry.getName();
            if (FileUtils.isClassFile(Paths.get(name))) {
              String descriptor = DescriptorUtils.guessTypeDescriptor(name);
              builder.put(descriptor, ByteStreams.toByteArray(stream));
            }
          });
      resources = builder.build();
    }

    @Override
    public Set<String> getClassDescriptors() {
      return resources.keySet();
    }

    @Override
    public Resource getResource(String descriptor) {
      byte[] bytes = resources.get(descriptor);
      return bytes == null
          ? null
          : Resource.fromBytes(
              new EntryOrigin(descriptor, origin), bytes, Collections.singleton(descriptor));
    }
  }

  static class EntryOrigin extends Origin {
    final String descriptor;

    public EntryOrigin(String descriptor, Origin parent) {
      super(parent);
      this.descriptor = descriptor;
    }

    @Override
    public String part() {
      return descriptor;
    }
  }

  private static String title(String title, boolean desugar) {
    return "FrameworkIncremental" + (desugar ? title : "NoDesugar" + title);
  }

  private static void compileAll(
      Path input,
      InMemoryClassPathProvider provider,
      boolean desugar,
      Map<String, Resource> outputs,
      ExecutorService executor)
      throws IOException, CompilationException {
    long start = System.nanoTime();
    D8Output out =
        D8.run(
            D8Command.builder()
                .setMinApiLevel(API)
                .setIntermediate(true)
                .setMode(CompilationMode.DEBUG)
                .addProgramFiles(input)
                .addLibraryFiles(LIB)
                .setOutputMode(OutputMode.FilePerInputClass)
                .setEnableDesugaring(desugar)
                .build(),
            executor);
    printRuntimeNanoseconds(title("DexAll", desugar), System.nanoTime() - start);
    for (Resource resource : out.getDexResources()) {
      for (String descriptor : resource.getClassDescriptors()) {
        assert !outputs.containsKey(descriptor);
        if (provider.resources.containsKey(descriptor)) {
          outputs.put(descriptor, resource);
        }
      }
    }
  }

  private static void compileGroupsOf(
      int count,
      List<String> descriptors,
      InMemoryClassPathProvider provider,
      boolean desugar,
      Map<String, Resource> outputs,
      ExecutorService executor)
      throws IOException, CompilationException {
    descriptors.sort(String::compareTo);
    int increment = descriptors.size() / ITERATIONS;
    long start = System.nanoTime();
    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      int index = iteration * increment;
      List<byte[]> inputs = new ArrayList<>(count);
      for (int j = 0; j < count; j++) {
        provider.resources.get(descriptors.get(index + j));
      }
      D8Output out =
          D8.run(
              D8Command.builder()
                  .setMinApiLevel(API)
                  .setIntermediate(true)
                  .setMode(CompilationMode.DEBUG)
                  .addClassProgramData(inputs)
                  .addClasspathResourceProvider(provider)
                  .addLibraryFiles(LIB)
                  .setOutputMode(OutputMode.FilePerInputClass)
                  .setEnableDesugaring(desugar)
                  .build(),
              executor);
      for (Resource resource : out.getDexResources()) {
        for (String descriptor : resource.getClassDescriptors()) {
          if (provider.resources.containsKey(descriptor)) {
            outputs.put(descriptor, resource);
          }
        }
      }
    }
    printRuntimeNanoseconds(title("DexGroupsOf" + count, desugar), System.nanoTime() - start);
  }

  private static D8Output merge(
      boolean desugar,
      Map<String, Resource> outputs,
      ExecutorService executor)
      throws IOException, CompilationException {
    List<byte[]> bytes = new ArrayList<>(outputs.size());
    for (Resource input : outputs.values()) {
      try (InputStream inputStream = input.getStream()) {
        bytes.add(ByteStreams.toByteArray(inputStream));
      }
    }
    long start = System.nanoTime();
    D8Output out =
        D8.run(
            D8Command.builder()
                .setMinApiLevel(API)
                .setIntermediate(false)
                .setMode(CompilationMode.DEBUG)
                .addDexProgramData(bytes)
                .setOutputMode(OutputMode.Indexed)
                .setEnableDesugaring(false) // never need to desugar when merging dex.
                .build(),
            executor);
    printRuntimeNanoseconds(title("DexMerge", desugar), System.nanoTime() - start);
    return out;
  }

  public static void main(String[] args) throws IOException, CompilationException {
    boolean desugar = Arrays.asList(args).contains("--desugar");
    Path input = desugar ? JAR_NOT_DESUGARED : JAR_DESUGARED;
    InMemoryClassPathProvider provider = new InMemoryClassPathProvider(input);
    List<String> descriptors = new ArrayList<>(provider.getClassDescriptors());
    Map<String, Resource> outputs = new HashMap<>(provider.getClassDescriptors().size());
    int threads = Integer.min(Runtime.getRuntime().availableProcessors(), 16) / 2;
    ExecutorService executor = ThreadUtils.getExecutorService(threads);
    try {
      compileAll(input, provider, desugar, outputs, executor);
      compileGroupsOf(1, descriptors, provider, desugar, outputs, executor);
      compileGroupsOf(10, descriptors, provider, desugar, outputs, executor);
      compileGroupsOf(100, descriptors, provider, desugar, outputs, executor);
      merge(desugar, outputs, executor);
      // TODO: We should run dex2oat to verify the compilation.
    } finally {
      executor.shutdown();
    }
  }
}

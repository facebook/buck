// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

import static com.android.tools.r8.graph.ClassKind.CLASSPATH;
import static com.android.tools.r8.graph.ClassKind.LIBRARY;
import static com.android.tools.r8.graph.ClassKind.PROGRAM;

import com.android.tools.r8.ClassFileResourceProvider;
import com.android.tools.r8.Resource;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.ClassKind;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexClasspathClass;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexLibraryClass;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.JarApplicationReader;
import com.android.tools.r8.graph.JarClassFileReader;
import com.android.tools.r8.graph.LazyLoadedDexApplication;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.ClassProvider;
import com.android.tools.r8.utils.ClasspathClassCollection;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.DexVersion;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.LibraryClassCollection;
import com.android.tools.r8.utils.MainDexList;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class ApplicationReader {

  final InternalOptions options;
  final DexItemFactory itemFactory;
  final Timing timing;
  private final AndroidApp inputApp;

  public ApplicationReader(AndroidApp inputApp, InternalOptions options, Timing timing) {
    this.options = options;
    itemFactory = options.itemFactory;
    this.timing = timing;
    this.inputApp = inputApp;
  }

  public DexApplication read() throws IOException, ExecutionException {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      return read(executor);
    } finally {
      executor.shutdown();
    }
  }

  public final DexApplication read(ExecutorService executorService)
      throws IOException, ExecutionException {
    timing.begin("DexApplication.read");
    final LazyLoadedDexApplication.Builder builder = DexApplication.builder(itemFactory, timing);
    try {
      List<Future<?>> futures = new ArrayList<>();
      // Still preload some of the classes, primarily for two reasons:
      // (a) class lazy loading is not supported for DEX files
      //     now and current implementation of parallel DEX file
      //     loading will be lost with on-demand class loading.
      // (b) some of the class file resources don't provide information
      //     about class descriptor.
      // TODO: try and preload less classes.
      readProguardMap(builder, executorService, futures);
      readMainDexList(builder, executorService, futures);
      ClassReader classReader = new ClassReader(executorService, futures);
      classReader.readSources();
      ThreadUtils.awaitFutures(futures);
      classReader.initializeLazyClassCollection(builder);
    } finally {
      timing.end();
    }
    return builder.build();
  }

  private int verifyOrComputeMinApiLevel(int computedMinApiLevel, DexFile file) {
    DexVersion version = DexVersion.getDexVersion(file.getDexVersion());
    if (options.minApiLevel == AndroidApiLevel.getDefault().getLevel()) {
      computedMinApiLevel = Math
          .max(computedMinApiLevel, AndroidApiLevel.getMinAndroidApiLevel(version).getLevel());
    } else if (!version
        .matchesApiLevel(AndroidApiLevel.getAndroidApiLevel(options.minApiLevel))) {
      throw new CompilationError("Dex file with version '" + version.getIntValue() +
          "' cannot be used with min sdk level '" + options.minApiLevel + "'.");
    }
    return computedMinApiLevel;
  }

  private void readProguardMap(DexApplication.Builder<?> builder, ExecutorService executorService,
      List<Future<?>> futures) {
    // Read the Proguard mapping file in parallel with DexCode and DexProgramClass items.
    if (inputApp.hasProguardMap()) {
      futures.add(executorService.submit(() -> {
        try (InputStream map = inputApp.getProguardMap()) {
          builder.setProguardMap(ClassNameMapper.mapperFromInputStream(map));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }));
    }
  }

  private void readMainDexList(DexApplication.Builder<?> builder, ExecutorService executorService,
      List<Future<?>> futures) {
    if (inputApp.hasMainDexList()) {
      futures.add(executorService.submit(() -> {
        for (Resource resource : inputApp.getMainDexListResources()) {
          try (InputStream input = resource.getStream()) {
            builder.addToMainDexList(MainDexList.parse(input, itemFactory));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }

        builder.addToMainDexList(
            inputApp.getMainDexClasses()
                .stream()
                .map(clazz -> itemFactory.createType(DescriptorUtils.javaTypeToDescriptor(clazz)))
                .collect(Collectors.toList()));
      }));
    }
  }

  private final class ClassReader {
    private final ExecutorService executorService;
    private final List<Future<?>> futures;

    // We use concurrent queues to collect classes
    // since the classes can be collected concurrently.
    private final Queue<DexProgramClass> programClasses = new ConcurrentLinkedQueue<>();
    private final Queue<DexClasspathClass> classpathClasses = new ConcurrentLinkedQueue<>();
    private final Queue<DexLibraryClass> libraryClasses = new ConcurrentLinkedQueue<>();
    // Jar application reader to share across all class readers.
    private final JarApplicationReader application = new JarApplicationReader(options);

    ClassReader(ExecutorService executorService, List<Future<?>> futures) {
      this.executorService = executorService;
      this.futures = futures;
    }

    private <T extends DexClass> void readDexSources(List<Resource> dexSources,
        ClassKind classKind, Queue<T> classes) throws IOException {
      if (dexSources.size() > 0) {
        List<DexFileReader> fileReaders = new ArrayList<>(dexSources.size());
        int computedMinApiLevel = options.minApiLevel;
        for (Resource input : dexSources) {
          try (InputStream is = input.getStream()) {
            DexFile file = new DexFile(is);
            computedMinApiLevel = verifyOrComputeMinApiLevel(computedMinApiLevel, file);
            fileReaders.add(new DexFileReader(input.origin, file, classKind, itemFactory));
          }
        }
        options.minApiLevel = computedMinApiLevel;
        for (DexFileReader reader : fileReaders) {
          DexFileReader.populateIndexTables(reader);
        }
        // Read the DexCode items and DexProgramClass items in parallel.
        if (!options.skipReadingDexCode) {
          for (DexFileReader reader : fileReaders) {
            futures.add(executorService.submit(() -> {
              reader.addCodeItemsTo();  // Depends on Everything for parsing.
              reader.addClassDefsTo(
                  classKind.bridgeConsumer(classes::add)); // Depends on Methods, Code items etc.
            }));
          }
        }
      }
    }

    private <T extends DexClass> void readClassSources(List<Resource> classSources,
        ClassKind classKind, Queue<T> classes) {
      JarClassFileReader reader = new JarClassFileReader(
          application, classKind.bridgeConsumer(classes::add));
      // Read classes in parallel.
      for (Resource input : classSources) {
        futures.add(executorService.submit(() -> {
          try (InputStream is = input.getStream()) {
            reader.read(input.origin, classKind, is);
          }
          // No other way to have a void callable, but we want the IOException from the previous
          // line to be wrapped into an ExecutionException.
          return null;
        }));
      }
    }

    void readSources() throws IOException {
      readDexSources(inputApp.getDexProgramResources(), PROGRAM, programClasses);
      readClassSources(inputApp.getClassProgramResources(), PROGRAM, programClasses);
    }

    private <T extends DexClass> ClassProvider<T> buildClassProvider(ClassKind classKind,
        Queue<T> preloadedClasses, List<ClassFileResourceProvider> resourceProviders,
        JarApplicationReader reader) {
      List<ClassProvider<T>> providers = new ArrayList<>();

      // Preloaded classes.
      if (!preloadedClasses.isEmpty()) {
        providers.add(ClassProvider.forPreloadedClasses(classKind, preloadedClasses));
      }

      // Class file resource providers.
      for (ClassFileResourceProvider provider : resourceProviders) {
        providers.add(ClassProvider.forClassFileResources(classKind, provider, reader));
      }

      // Combine if needed.
      if (providers.isEmpty()) {
        return null;
      }
      return providers.size() == 1 ? providers.get(0)
          : ClassProvider.combine(classKind, providers);
    }

    void initializeLazyClassCollection(LazyLoadedDexApplication.Builder builder) {
      // Add all program classes to the builder.
      for (DexProgramClass clazz : programClasses) {
        builder.addProgramClass(clazz.asProgramClass());
      }

      // Create classpath class collection if needed.
      ClassProvider<DexClasspathClass> classpathClassProvider = buildClassProvider(CLASSPATH,
          classpathClasses, inputApp.getClasspathResourceProviders(), application);
      if (classpathClassProvider != null) {
        builder.setClasspathClassCollection(new ClasspathClassCollection(classpathClassProvider));
      }

      // Create library class collection if needed.
      ClassProvider<DexLibraryClass> libraryClassProvider = buildClassProvider(LIBRARY,
          libraryClasses, inputApp.getLibraryResourceProviders(), application);
      if (libraryClassProvider != null) {
        builder.setLibraryClassCollection(new LibraryClassCollection(libraryClassProvider));
      }
    }
  }
}

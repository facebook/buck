/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android.dalvik;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.classes.ClasspathTraversal;
import com.facebook.buck.jvm.java.classes.ClasspathTraverser;
import com.facebook.buck.jvm.java.classes.DefaultClasspathTraverser;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.facebook.buck.jvm.java.classes.FileLikes;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Implementation of {@link ZipSplitter} that uses estimates from {@link DalvikStatsTool} to
 * determine how many classes to pack into a dex.
 *
 * <p>It does two passes through the .class files:
 *
 * <ul>
 *   <li>During the first pass, it uses the {@code requiredInPrimaryZip} predicate to filter the set
 *       of classes that <em>must</em> be included in the primary dex. These classes are added to
 *       the primary zip.
 *   <li>During the second pass, classes that were not matched during the first pass are added to
 *       zips as space allows. This is a simple, greedy algorithm.
 * </ul>
 */
public class DalvikAwareZipSplitter implements ZipSplitter {
  private static final Logger LOG = Logger.get(DalvikAwareZipSplitter.class);

  private final ProjectFilesystem filesystem;
  private final Set<Path> inFiles;
  private final Path outPrimary;
  private final Predicate<String> requiredInPrimaryZip;
  private final Path reportDir;
  private final long linearAllocLimit;
  private final long methodRefCountBufferSpace;
  private final long fieldRefCountBufferSpace;
  private final DalvikStatsCache dalvikStatsCache;
  private final DexSplitStrategy dexSplitStrategy;
  @Nullable private final ImmutableMultimap<String, APKModule> classPathToDexStore;

  private final MySecondaryDexHelper secondaryDexWriter;
  private final Map<APKModule, MySecondaryDexHelper> additionalDexWriters;
  private final APKModule rootModule;

  @Nullable private DalvikAwareOutputStreamHelper primaryOut;

  private DalvikAwareZipSplitter(
      ProjectFilesystem filesystem,
      Set<Path> inFiles,
      Path outPrimary,
      Path outSecondaryDir,
      String secondaryPattern,
      Path outDexStoresDir,
      long linearAllocLimit,
      long methodRefCountBufferSpace,
      long fieldRefCountBufferSpace,
      Predicate<String> requiredInPrimaryZip,
      ImmutableMultimap<APKModule, String> additionalDexStoreSets,
      APKModule rootAPKModule,
      DexSplitStrategy dexSplitStrategy,
      Path reportDir) {
    if (linearAllocLimit <= 0) {
      throw new HumanReadableException("linear_alloc_hard_limit must be greater than zero.");
    }
    this.filesystem = filesystem;
    this.inFiles = ImmutableSet.copyOf(inFiles);
    this.outPrimary = outPrimary;
    this.secondaryDexWriter =
        new MySecondaryDexHelper("secondary", outSecondaryDir, secondaryPattern);
    this.additionalDexWriters = new HashMap<>();
    this.requiredInPrimaryZip = requiredInPrimaryZip;
    this.classPathToDexStore = additionalDexStoreSets.inverse();
    for (APKModule dexStore : additionalDexStoreSets.keySet()) {
      if (!dexStore.equals(rootAPKModule)) {
        additionalDexWriters.put(
            dexStore,
            new MySecondaryDexHelper(
                dexStore.getCanaryClassName(),
                outDexStoresDir.resolve(dexStore.getName()),
                secondaryPattern));
      }
    }
    this.rootModule = rootAPKModule;
    this.reportDir = reportDir;
    this.dexSplitStrategy = dexSplitStrategy;
    this.linearAllocLimit = linearAllocLimit;
    this.methodRefCountBufferSpace = methodRefCountBufferSpace;
    this.fieldRefCountBufferSpace = fieldRefCountBufferSpace;
    this.dalvikStatsCache = new DalvikStatsCache();
  }

  public static DalvikAwareZipSplitter splitZip(
      ProjectFilesystem filesystem,
      Set<Path> inFiles,
      Path outPrimary,
      Path outSecondaryDir,
      String secondaryPattern,
      Path outDexStoresDir,
      long linearAllocLimit,
      long methodRefCountBufferSpace,
      long fieldRefCountBufferSpace,
      Predicate<String> requiredInPrimaryZip,
      ImmutableMultimap<APKModule, String> additionalDexStoreSets,
      APKModule rootAPKModule,
      DexSplitStrategy dexSplitStrategy,
      Path reportDir) {
    return new DalvikAwareZipSplitter(
        filesystem,
        inFiles,
        outPrimary,
        outSecondaryDir,
        secondaryPattern,
        outDexStoresDir,
        linearAllocLimit,
        methodRefCountBufferSpace,
        fieldRefCountBufferSpace,
        requiredInPrimaryZip,
        additionalDexStoreSets,
        rootAPKModule,
        dexSplitStrategy,
        reportDir);
  }

  @Override
  public ImmutableMultimap<APKModule, Path> execute() throws IOException {
    ClasspathTraverser classpathTraverser = new DefaultClasspathTraverser();

    // Start out by writing the primary zip and recording which entries were added to it.
    primaryOut = newZipOutput(outPrimary);
    secondaryDexWriter.reset();

    // Java 9 adds module info, which we don't need on Android as we don't run with the Java 9+
    // runtime, and we need to ignore these classes so we don't get clashes between different
    // jars.
    ImmutableSet<String> ignoredClassNames = ImmutableSet.of("META-INF/versions/9/module-info");

    List<String> additionalDexStoreEntries = new ArrayList<>();

    // Iterate over all of the inFiles and add all entries that match the requiredInPrimaryZip
    // predicate.
    LOG.verbose("Traversing classpath for primary zip");

    classpathTraverser.traverse(
        new ClasspathTraversal(inFiles, filesystem.getRootPath(), filesystem.getIgnoredPaths()) {
          @Override
          public void visit(FileLike entry) throws IOException {
            LOG.verbose("Visiting " + entry.getRelativePath());

            String relativePath = entry.getRelativePath();
            if (!relativePath.endsWith(".class")) {
              // We don't need resources in dex jars, so just drop them.
              return;
            }
            String classPath = relativePath.replaceAll("\\.class$", "");

            String key = FileLikes.getFileNameWithoutClassSuffix(entry);
            if (ignoredClassNames.contains(key)) {
              return;
            }

            Objects.requireNonNull(primaryOut);
            Objects.requireNonNull(classPathToDexStore);

            if (requiredInPrimaryZip.test(relativePath)) {
              primaryOut.putEntry(entry);
            } else {
              ImmutableCollection<APKModule> containingModule = classPathToDexStore.get(classPath);
              if (!containingModule.isEmpty()) {
                if (containingModule.size() > 1) {
                  throw new IllegalStateException(
                      String.format(
                          "classpath %s is contained in multiple dex stores: %s",
                          classPath, classPathToDexStore.get(classPath).asList().toString()));
                }
                APKModule dexStore = containingModule.iterator().next();
                if (!dexStore.equals(rootModule)) {
                  MySecondaryDexHelper dexHelper = additionalDexWriters.get(dexStore);
                  Objects.requireNonNull(dexHelper);
                  dexHelper.getOutputToWriteTo(entry).putEntry(entry);
                  additionalDexStoreEntries.add(relativePath);
                }
              }
            }
          }
        });

    LOG.verbose("Traversing classpath for secondary zip");

    // Now that all of the required entries have been added to the primary zip, fill the rest of
    // the zip up with the remaining entries.
    classpathTraverser.traverse(
        new ClasspathTraversal(inFiles, filesystem.getRootPath(), filesystem.getIgnoredPaths()) {
          @Override
          public void visit(FileLike entry) throws IOException {
            Objects.requireNonNull(primaryOut);
            String relativePath = entry.getRelativePath();

            // skip if it is the primary dex, is part of a modular dex store, or is not a class file
            if (primaryOut.containsEntry(entry)
                || additionalDexStoreEntries.contains(relativePath)) {
              return;
            }

            LOG.verbose("Visiting " + entry.getRelativePath());

            // Even if we have started writing a secondary dex, we still check if there is any
            // leftover
            // room in the primary dex for the current entry in the traversal.
            if (dexSplitStrategy == DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE
                && primaryOut.canPutEntry(entry)) {
              primaryOut.putEntry(entry);
            } else {
              secondaryDexWriter.getOutputToWriteTo(entry).putEntry(entry);
            }
          }
        });
    primaryOut.close();
    secondaryDexWriter.close();

    ImmutableMultimap.Builder<APKModule, Path> outputFilesBuilder = ImmutableMultimap.builder();
    APKModule secondaryDexStore = rootModule;
    outputFilesBuilder.putAll(secondaryDexStore, secondaryDexWriter.getFiles());
    for (Map.Entry<APKModule, MySecondaryDexHelper> entry : additionalDexWriters.entrySet()) {
      if (!entry.getKey().equals(secondaryDexStore)) {
        entry.getValue().close();
        outputFilesBuilder.putAll(entry.getKey(), entry.getValue().getFiles());
      }
    }
    return outputFilesBuilder.build();
  }

  private DalvikAwareOutputStreamHelper newZipOutput(Path file) throws IOException {
    return new DalvikAwareOutputStreamHelper(
        filesystem.resolve(file),
        linearAllocLimit,
        methodRefCountBufferSpace,
        fieldRefCountBufferSpace,
        reportDir,
        dalvikStatsCache);
  }

  private class MySecondaryDexHelper extends SecondaryDexHelper<DalvikAwareOutputStreamHelper> {

    MySecondaryDexHelper(String storeName, Path outSecondaryDir, String secondaryPattern) {
      super(storeName, outSecondaryDir, secondaryPattern);
    }

    @Override
    protected DalvikAwareOutputStreamHelper newZipOutput(Path file) throws IOException {
      return DalvikAwareZipSplitter.this.newZipOutput(file);
    }
  }
}

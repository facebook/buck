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

package com.facebook.buck.android;

import static com.google.common.collect.Ordering.natural;

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.MoreStrings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.SortedSetMultimap;
import com.google.common.collect.TreeMultimap;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MergeAndroidResourcesStep implements Step {

  private final ImmutableList<HasAndroidResourceDeps> androidResourceDeps;
  private final Optional<Path> uberRDotTxt;
  private final boolean warnMissingResource;
  private final Path outputDir;

  /**
   * Merges text symbols files from {@code aapt} for each of the input {@code android_resource}
   * into a set of resources per R.java package and writes an {@code R.java} file per package under
   * the output directory. Also, if {@code uberRDotTxt} is present, the IDs in the output
   * {@code R.java} file will be taken from the {@code R.txt} file.
   */
  @VisibleForTesting
  MergeAndroidResourcesStep(
      List<HasAndroidResourceDeps> androidResourceDeps,
      Optional<Path> uberRDotTxt,
      boolean warnMissingResource,
      Path outputDir) {
    this.androidResourceDeps = ImmutableList.copyOf(androidResourceDeps);
    this.uberRDotTxt = uberRDotTxt;
    this.warnMissingResource = warnMissingResource;
    this.outputDir = outputDir;
  }

  public static MergeAndroidResourcesStep createStepForDummyRDotJava(
      List<HasAndroidResourceDeps> androidResourceDeps,
      Path outputDir) {
    return new MergeAndroidResourcesStep(
        androidResourceDeps,
        Optional.<Path>absent(),
        false,
        outputDir);
  }

  public static MergeAndroidResourcesStep createStepForUberRDotJava(
      List<HasAndroidResourceDeps> androidResourceDeps,
      Path uberRDotTxt,
      boolean warnMissingResource,
      Path outputDir) {
    return new MergeAndroidResourcesStep(
        androidResourceDeps,
        Optional.of(uberRDotTxt),
        warnMissingResource,
        outputDir);
  }

  public ImmutableSet<SourcePath> getRDotJavaFiles() {
    return FluentIterable.from(androidResourceDeps)
        .transform(HasAndroidResourceDeps.TO_R_DOT_JAVA_PACKAGE)
        .transform(
            new Function<String, Path>() {
              @Override
              public Path apply(String input) {
                return getPathToRDotJava(input);
              }
            })
        .transform(SourcePaths.TO_SOURCE_PATH)
        .toSet();
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      doExecute(context);
      return 0;
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
  }

  private void doExecute(ExecutionContext context) throws IOException {
    // In order to convert a symbols file to R.java, all resources of the same type are grouped
    // into a static class of that name. The static class contains static values that correspond to
    // the resource (type, name, value) tuples. See RDotTxtEntry.
    //
    // The first step is to merge symbol files of the same package type and resource type/name.
    // That is, within a package type, each resource type/name pair must be unique. If there are
    // multiple pairs, only one will be written to the R.java file.
    //
    // Because the resulting files do not match their respective resources.arsc, the values are
    // meaningless and do not represent the usable final result.  This is why the R.java file is
    // written without using final so that javac will not inline the values.  Unfortunately,
    // though Robolectric doesn't read resources.arsc, it does assert that all the R.java resource
    // ids are unique.  This forces us to re-enumerate new unique ids.
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    ImmutableMap.Builder<Path, String> rDotTxtToPackage = ImmutableMap.builder();
    for (HasAndroidResourceDeps res : androidResourceDeps) {
      rDotTxtToPackage.put(
          res.getPathToTextSymbolsFile(),
          res.getRDotJavaPackage());
    }
    Optional<ImmutableMap<RDotTxtEntry, String>> uberRDotTxtIds;
    if (uberRDotTxt.isPresent()) {
      // re-assign Ids
      uberRDotTxtIds = Optional.of(FluentIterable.from(
          RDotTxtEntry.readResources(context, uberRDotTxt.get()))
          .toMap(
              new Function<RDotTxtEntry, String>() {
                @Override
                public String apply(RDotTxtEntry input) {
                  return input.idValue;
                }
              }));
    } else {
      uberRDotTxtIds = Optional.absent();
    }

    ImmutableMap<Path, String> symbolsFileToRDotJavaPackage = rDotTxtToPackage.build();

    SortedSetMultimap<String, RDotTxtEntry> rDotJavaPackageToResources = sortSymbols(
        symbolsFileToRDotJavaPackage,
        uberRDotTxtIds,
        warnMissingResource,
        context);

    writePerPackageRDotJava(rDotJavaPackageToResources, filesystem);
    Set<String> emptyPackages = Sets.difference(
        ImmutableSet.copyOf(symbolsFileToRDotJavaPackage.values()),
        rDotJavaPackageToResources.keySet());

    if (!emptyPackages.isEmpty()) {
      writeEmptyRDotJavaForPackages(emptyPackages, filesystem);
    }
  }

  private void writeEmptyRDotJavaForPackages(
      Set<String> rDotJavaPackages,
      ProjectFilesystem filesystem) throws IOException {
    for (String rDotJavaPackage : rDotJavaPackages) {
      Path outputFile = getPathToRDotJava(rDotJavaPackage);
      filesystem.mkdirs(outputFile.getParent());
      filesystem.writeContentsToPath(
          String.format("package %s;\n\npublic class R {}\n", rDotJavaPackage),
          outputFile);
    }
  }

  @VisibleForTesting
  void writePerPackageRDotJava(
      SortedSetMultimap<String, RDotTxtEntry> packageToResources,
      ProjectFilesystem filesystem) throws IOException {
    for (String rDotJavaPackage : packageToResources.keySet()) {
      Path outputFile = getPathToRDotJava(rDotJavaPackage);
      filesystem.mkdirs(outputFile.getParent());
      try (PrintWriter writer = new PrintWriter(filesystem.newFileOutputStream(outputFile))) {
        writer.format("package %s;\n\n", rDotJavaPackage);
        writer.println("public class R {\n");

        RDotTxtEntry.RType lastType = null;

        for (RDotTxtEntry res : packageToResources.get(rDotJavaPackage)) {
          RDotTxtEntry.RType type = res.type;
          if (!type.equals(lastType)) {
            // If the previous type needs to be closed, close it.
            if (lastType != null) {
              writer.println("  }\n");
            }

            // Now start the block for the new type.
            writer.format("  public static class %s {\n", type);
            lastType = type;
          }

          // Write out the resource.
          // Write as an int.
          writer.format(
              "    public static%s%s %s=%s;\n",
              uberRDotTxt.isPresent() ? " final " : " ",
              res.idType,
              res.name,
              res.idValue);
        }

        // If some type was written (e.g., the for loop was entered), then the last type needs to be
        // closed.
        if (lastType != null) {
          writer.println("  }\n");
        }

        // Close the class definition.
        writer.println("}");
      }
    }
  }

  @VisibleForTesting
  static SortedSetMultimap<String, RDotTxtEntry> sortSymbols(
      Map<Path, String> symbolsFileToRDotJavaPackage,
      Optional<ImmutableMap<RDotTxtEntry, String>> uberRDotTxtIds,
      boolean warnMissingResource,
      ExecutionContext context) {
    // If we're reenumerating, start at 0x7f01001 so that the resulting file is human readable.
    // This value range (0x7f010001 - ...) is easier to spot as an actual resource id instead of
    // other values in styleable which can be enumerated integers starting at 0.
    Map<RDotTxtEntry, String> finalIds = null;
    IntEnumerator enumerator = null;
    if (uberRDotTxtIds.isPresent()) {
      finalIds = uberRDotTxtIds.get();
    } else {
      enumerator = new IntEnumerator(0x7f01001);
    }

    SortedSetMultimap<String, RDotTxtEntry> rDotJavaPackageToSymbolsFiles = TreeMultimap.create();
    for (Map.Entry<Path, String> entry : symbolsFileToRDotJavaPackage.entrySet()) {
      Path symbolsFile = entry.getKey();

      // Read the symbols file and parse each line as a Resource.
      List<String> linesInSymbolsFile;
      try {
        linesInSymbolsFile =
            FluentIterable.from(context.getProjectFilesystem().readLines(symbolsFile))
                .filter(MoreStrings.NON_EMPTY)
                .toList();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      String packageName = entry.getValue();
      for (String line : linesInSymbolsFile) {
        Optional<RDotTxtEntry> parsedEntry = RDotTxtEntry.parse(line);
        Preconditions.checkState(parsedEntry.isPresent(), "Should be able to match '%s'.", line);

        // We're only doing the remapping so Roboelectric is happy and it is already ignoring the
        // id references found in the styleable section.  So let's do that as well so we don't have
        // to get fancier than is needed.  That is, just re-enumerate all app-level resource ids
        // and ignore everything else, allowing the styleable references to be messed up.
        RDotTxtEntry resource = parsedEntry.get();
        if (uberRDotTxtIds.isPresent()) {
          Preconditions.checkNotNull(finalIds);
          if (!finalIds.containsKey(resource)) {
            if (warnMissingResource) {
              context.postEvent(ConsoleEvent.warning(
                      "Cannot find resource '%s' in the uber R.txt.", resource));
            }
            continue;
          }
          resource = resource.copyWithNewIdValue(finalIds.get(resource));
        } else if (resource.idValue.startsWith("0x7f")) {
          Preconditions.checkNotNull(enumerator);
          resource = resource.copyWithNewIdValue(String.format("0x%08x", enumerator.next()));
        }

        rDotJavaPackageToSymbolsFiles.put(packageName, resource);
      }
    }
    return rDotJavaPackageToSymbolsFiles;
  }

  @Override
  public String getShortName() {
    return "android-res-merge";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    ImmutableList<String> resources =
        FluentIterable.from(androidResourceDeps)
            .transform(Functions.toStringFunction())
            .toSortedList(natural());
    return getShortName() + " " + Joiner.on(' ').join(resources);
  }

  private Path getPathToRDotJava(String rDotJavaPackage) {
    return outputDir.resolve(rDotJavaPackage.replace(".", "/")).resolve("R.java");
  }

  private static class IntEnumerator {
    private int value;

    public IntEnumerator(int start) {
      value = start;
    }

    public int next() {
      Preconditions.checkState(value < Integer.MAX_VALUE, "Stop goofing off");
      return value++;
    }
  }

}

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
import com.facebook.buck.android.aapt.RDotTxtEntry.IdType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.ThrowingPrintWriter;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.SortedSetMultimap;
import com.google.common.collect.TreeMultimap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class MergeAndroidResourcesStep implements Step {
  private static final Logger LOG = Logger.get(MergeAndroidResourcesStep.class);

  private final ProjectFilesystem filesystem;
  private final SourcePathResolver pathResolver;
  private final ImmutableList<HasAndroidResourceDeps> androidResourceDeps;
  private final Optional<Path> uberRDotTxt;
  private final Path outputDir;
  private final boolean forceFinalResourceIds;
  private final EnumSet<RType> bannedDuplicateResourceTypes;
  private final Optional<Path> duplicateResourceWhitelistPath;
  private final Optional<String> unionPackage;
  private final String rName;
  private final boolean useOldStyleableFormat;
  private final Optional<Path> overrideSymbolsPath;
  private final boolean skipNonUnionRDotJava;

  /**
   * Merges text symbols files from {@code aapt} for each of the input {@code android_resource} into
   * a set of resources per R.java package and writes an {@code R.java} file per package under the
   * output directory. Also, if {@code uberRDotTxt} is present, the IDs in the output {@code R.java}
   * file will be taken from the {@code R.txt} file.
   */
  @VisibleForTesting
  MergeAndroidResourcesStep(
      ProjectFilesystem filesystem,
      SourcePathResolver pathResolver,
      List<HasAndroidResourceDeps> androidResourceDeps,
      Optional<Path> uberRDotTxt,
      Path outputDir,
      boolean forceFinalResourceIds,
      EnumSet<RType> bannedDuplicateResourceTypes,
      Optional<Path> duplicateResourceWhitelistPath,
      Optional<Path> overrideSymbolsPath,
      Optional<String> unionPackage,
      Optional<String> rName,
      boolean useOldStyleableFormat,
      boolean skipNonUnionRDotJava) {
    this.filesystem = filesystem;
    this.pathResolver = pathResolver;
    this.androidResourceDeps = ImmutableList.copyOf(androidResourceDeps);
    this.uberRDotTxt = uberRDotTxt;
    this.outputDir = outputDir;
    this.forceFinalResourceIds = forceFinalResourceIds;
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.duplicateResourceWhitelistPath = duplicateResourceWhitelistPath;
    this.unionPackage = unionPackage;
    this.overrideSymbolsPath = overrideSymbolsPath;
    this.rName = rName.orElse("R");
    this.useOldStyleableFormat = useOldStyleableFormat;
    this.skipNonUnionRDotJava = skipNonUnionRDotJava;
  }

  public static MergeAndroidResourcesStep createStepForDummyRDotJava(
      ProjectFilesystem filesystem,
      SourcePathResolver pathResolver,
      List<HasAndroidResourceDeps> androidResourceDeps,
      Path outputDir,
      boolean forceFinalResourceIds,
      Optional<String> unionPackage,
      Optional<String> rName,
      boolean useOldStyleableFormat,
      boolean skipNonUnionRDotJava) {
    return new MergeAndroidResourcesStep(
        filesystem,
        pathResolver,
        androidResourceDeps,
        /* uberRDotTxt */ Optional.empty(),
        outputDir,
        forceFinalResourceIds,
        /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
        Optional.empty(),
        Optional.empty(),
        unionPackage,
        rName,
        useOldStyleableFormat,
        skipNonUnionRDotJava);
  }

  public static MergeAndroidResourcesStep createStepForUberRDotJava(
      ProjectFilesystem filesystem,
      SourcePathResolver pathResolver,
      List<HasAndroidResourceDeps> androidResourceDeps,
      Path uberRDotTxt,
      Path outputDir,
      EnumSet<RType> bannedDuplicateResourceTypes,
      Optional<Path> duplicateResourceWhitelistPath,
      Optional<Path> overrideSymbolsPath,
      Optional<String> unionPackage) {
    return new MergeAndroidResourcesStep(
        filesystem,
        pathResolver,
        androidResourceDeps,
        Optional.of(uberRDotTxt),
        outputDir,
        /* forceFinalResourceIds */ true,
        bannedDuplicateResourceTypes,
        duplicateResourceWhitelistPath,
        overrideSymbolsPath,
        unionPackage,
        /* rName */ Optional.empty(),
        /* useOldStyleableFormat */ false,
        /* skipNonUnionRDotJava */ false);
  }

  public ImmutableSortedSet<Path> getRDotJavaFiles() {
    FluentIterable<String> packages =
        FluentIterable.from(
            unionPackage.map(Collections::singletonList).orElse(Collections.emptyList()));

    if (!skipNonUnionRDotJava) {
      packages =
          packages.append(
              FluentIterable.from(androidResourceDeps)
                  .transform(HasAndroidResourceDeps::getRDotJavaPackage));
    }

    return packages.transform(this::getPathToRDotJava).toSortedSet(natural());
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    try {
      // In order to convert a symbols file to R.java, all resources of the same type are grouped
      // into a static class of that name. The static class contains static values that correspond
      // to the resource (type, name, value) tuples. See RDotTxtEntry.
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
      ImmutableMap.Builder<Path, String> rDotTxtToPackage = ImmutableMap.builder();
      ImmutableMap.Builder<Path, HasAndroidResourceDeps> symbolsFileToResourceDeps =
          ImmutableMap.builder();
      for (HasAndroidResourceDeps res : androidResourceDeps) {
        Path rDotTxtPath =
            filesystem.relativize(pathResolver.getAbsolutePath(res.getPathToTextSymbolsFile()));
        rDotTxtToPackage.put(rDotTxtPath, res.getRDotJavaPackage());
        symbolsFileToResourceDeps.put(rDotTxtPath, res);
      }
      Optional<ImmutableMap<RDotTxtEntry, String>> uberRDotTxtIds;
      if (uberRDotTxt.isPresent()) {
        // re-assign Ids
        uberRDotTxtIds =
            Optional.of(
                FluentIterable.from(RDotTxtEntry.readResources(filesystem, uberRDotTxt.get()))
                    .toMap(input -> input.idValue));
      } else {
        uberRDotTxtIds = Optional.empty();
      }

      ImmutableMap<Path, String> symbolsFileToRDotJavaPackage = rDotTxtToPackage.build();

      Optional<SetMultimap<String, RDotTxtEntry>> overrideSymbols =
          overrideSymbolsPath.isPresent()
              ? loadOverrideSymbols(overrideSymbolsPath.get())
              : Optional.empty();

      ImmutableSet<String> duplciateResourceWhitelist =
          (duplicateResourceWhitelistPath.isPresent())
              ? ImmutableSet.copyOf(filesystem.readLines(duplicateResourceWhitelistPath.get()))
              : ImmutableSet.of();

      SortedSetMultimap<String, RDotTxtEntry> rDotJavaPackageToResources =
          sortSymbols(
              symbolsFileToRDotJavaPackage,
              uberRDotTxtIds,
              symbolsFileToResourceDeps.build(),
              overrideSymbols,
              bannedDuplicateResourceTypes,
              duplciateResourceWhitelist,
              filesystem,
              useOldStyleableFormat);

      ImmutableSet.Builder<String> requiredPackages = ImmutableSet.builder();

      // Create a temporary list as the multimap
      // will be concurrently modified below.
      ArrayList<Entry<String, RDotTxtEntry>> entries =
          new ArrayList<>(rDotJavaPackageToResources.entries());

      if (skipNonUnionRDotJava) {
        Preconditions.checkArgument(
            unionPackage.isPresent(),
            "union_package should be specified if skip_non_union_r_dot_java is set");

        // If skip_non_union_r_dot_java is true remove all packages except union package
        rDotJavaPackageToResources = TreeMultimap.create();

      } else {
        requiredPackages.addAll(symbolsFileToRDotJavaPackage.values());
      }

      // If a resource_union_package was specified, copy all resource into that package,
      // unless they are already present.
      if (unionPackage.isPresent()) {
        String unionPackageName = unionPackage.get();
        requiredPackages.add(unionPackageName);

        for (Map.Entry<String, RDotTxtEntry> entry : entries) {
          if (!rDotJavaPackageToResources.containsEntry(unionPackageName, entry.getValue())) {
            rDotJavaPackageToResources.put(unionPackageName, entry.getValue());
          }
        }
      }

      writePerPackageRDotJava(rDotJavaPackageToResources, filesystem);
      Set<String> emptyPackages =
          Sets.difference(requiredPackages.build(), rDotJavaPackageToResources.keySet());

      if (!emptyPackages.isEmpty()) {
        writeEmptyRDotJavaForPackages(emptyPackages, filesystem);
      }
      return StepExecutionResults.SUCCESS;
    } catch (DuplicateResourceException e) {
      return StepExecutionResult.of(1, Optional.of(e.getMessage()));
    }
  }

  private Optional<SetMultimap<String, RDotTxtEntry>> loadOverrideSymbols(Path path)
      throws IOException {
    if (!Files.isRegularFile(path)) {
      LOG.info("Override-symbols file %s is not present or not regular.  Skipping.", path);
      return Optional.empty();
    }
    ImmutableSetMultimap.Builder<String, RDotTxtEntry> symbolsBuilder =
        ImmutableSetMultimap.builder();
    JsonNode jsonData = ObjectMappers.READER.readTree(ObjectMappers.createParser(path));
    for (String packageName : (Iterable<String>) jsonData::fieldNames) {
      Iterator<JsonNode> rDotTxtLines = jsonData.get(packageName).elements();
      while (rDotTxtLines.hasNext()) {
        String rDotTxtLine = rDotTxtLines.next().asText();
        symbolsBuilder.put(packageName, parseEntryOrThrow(rDotTxtLine));
      }
    }
    return Optional.of(symbolsBuilder.build());
  }

  private void writeEmptyRDotJavaForPackages(
      Set<String> rDotJavaPackages, ProjectFilesystem filesystem) throws IOException {
    for (String rDotJavaPackage : rDotJavaPackages) {
      Path outputFile = getPathToRDotJava(rDotJavaPackage);
      filesystem.mkdirs(outputFile.getParent());
      filesystem.writeContentsToPath(
          String.format("package %s;\n\npublic class %s {}\n", rDotJavaPackage, rName), outputFile);
    }
  }

  @VisibleForTesting
  void writePerPackageRDotJava(
      SortedSetMultimap<String, RDotTxtEntry> packageToResources, ProjectFilesystem filesystem)
      throws IOException {
    for (String rDotJavaPackage : packageToResources.keySet()) {
      Path outputFile = getPathToRDotJava(rDotJavaPackage);
      filesystem.mkdirs(outputFile.getParent());
      try (ThrowingPrintWriter writer =
          new ThrowingPrintWriter(filesystem.newFileOutputStream(outputFile))) {
        writer.format("package %s;\n\n", rDotJavaPackage);
        writer.format("public class %s {\n", rName);

        ImmutableList.Builder<String> customDrawablesBuilder = ImmutableList.builder();
        ImmutableList.Builder<String> grayscaleImagesBuilder = ImmutableList.builder();
        RType lastType = null;

        for (RDotTxtEntry res : packageToResources.get(rDotJavaPackage)) {
          RType type = res.type;
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
              forceFinalResourceIds ? " final " : " ", res.idType, res.name, res.idValue);

          if (type == RType.DRAWABLE && res.customType == RDotTxtEntry.CustomDrawableType.CUSTOM) {
            customDrawablesBuilder.add(res.idValue);
          } else if (type == RType.DRAWABLE
              && res.customType == RDotTxtEntry.CustomDrawableType.GRAYSCALE_IMAGE) {
            grayscaleImagesBuilder.add(res.idValue);
          }
        }

        // If some type was written (e.g., the for loop was entered), then the last type needs to be
        // closed.
        if (lastType != null) {
          writer.println("  }\n");
        }

        ImmutableList<String> customDrawables = customDrawablesBuilder.build();
        if (customDrawables.size() > 0) {
          // Add a new field for the custom drawables.
          writer.format("  public static final int[] custom_drawables = ");
          writer.format("{ %s };\n", Joiner.on(",").join(customDrawables));
          writer.format("\n");
        }

        ImmutableList<String> grayscaleImages = grayscaleImagesBuilder.build();
        if (grayscaleImages.size() > 0) {
          // Add a new field for the custom drawables.
          writer.format("  public static final int[] grayscale_images = ");
          writer.format("{ %s };\n", Joiner.on(",").join(grayscaleImages));
          writer.format("\n");
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
      ImmutableMap<Path, HasAndroidResourceDeps> symbolsFileToResourceDeps,
      Optional<SetMultimap<String, RDotTxtEntry>> overrides,
      EnumSet<RType> bannedDuplicateResourceTypes,
      Set<String> duplicateResourceWhitelist,
      ProjectFilesystem filesystem,
      boolean useOldStyleableFormat)
      throws DuplicateResourceException {
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
    SortedSetMultimap<RDotTxtEntry, Path> bannedDuplicateResourceToSymbolsFiles =
        TreeMultimap.create();

    HashMap<RDotTxtEntry, RDotTxtEntry> resourceToIdValuesMap = new HashMap<>();

    // Expand the package overrides into per-package self-maps.
    // The self-maps are basically sets, but we need to be able to look up
    // the actual RDotTxtEntry objects to get their ids (which are not included in .equals()).
    Map<String, Map<RDotTxtEntry, RDotTxtEntry>> expandedPackageOverrides = ImmutableMap.of();
    if (overrides.isPresent()) {
      expandedPackageOverrides = new HashMap<>();
      Map<String, Map<RDotTxtEntry, RDotTxtEntry>> ovr = expandedPackageOverrides;
      overrides
          .get()
          .asMap()
          .forEach(
              (pkg, entries) ->
                  ovr.put(pkg, entries.stream().collect(Collectors.toMap(k -> k, v -> v))));
    }

    for (Map.Entry<Path, String> entry : symbolsFileToRDotJavaPackage.entrySet()) {
      Path symbolsFile = entry.getKey();
      // Read the symbols file and parse each line as a Resource.
      List<RDotTxtEntry> linesInSymbolsFile;
      try {
        linesInSymbolsFile =
            filesystem
                .readLines(symbolsFile)
                .stream()
                .filter(input -> !Strings.isNullOrEmpty(input))
                .map(MergeAndroidResourcesStep::parseEntryOrThrow)
                .collect(Collectors.toList());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      String packageName = entry.getValue();
      Map<RDotTxtEntry, RDotTxtEntry> packageOverrides =
          expandedPackageOverrides.getOrDefault(packageName, ImmutableMap.of());

      if (!packageOverrides.isEmpty()) {
        // RDotTxtEntry computes hash codes and checks equality only based on type and name,
        // so we can use simple map lookup to find the overridden resource entry.
        for (int i = 0; i < linesInSymbolsFile.size(); i++) {
          RDotTxtEntry mappedEntry = packageOverrides.get(linesInSymbolsFile.get(i));
          if (mappedEntry != null) {
            linesInSymbolsFile.set(i, mappedEntry);
          }
        }
      }

      for (int index = 0; index < linesInSymbolsFile.size(); index++) {
        RDotTxtEntry resource = linesInSymbolsFile.get(index);

        if (uberRDotTxtIds.isPresent()) {
          Preconditions.checkNotNull(finalIds);
          if (!finalIds.containsKey(resource)) {
            LOG.debug("Cannot find resource '%s' in the uber R.txt.", resource);
            continue;
          }
          resource = resource.copyWithNewIdValue(finalIds.get(resource));

        } else if (useOldStyleableFormat && resource.idValue.startsWith("0x7f")) {
          Preconditions.checkNotNull(enumerator);
          resource = resource.copyWithNewIdValue(String.format("0x%08x", enumerator.next()));

        } else if (useOldStyleableFormat) { // NOPMD  more readable this way, IMO.
          // Nothing extra to do in this case.

        } else if (resourceToIdValuesMap.containsKey(resource)) {
          resource = resourceToIdValuesMap.get(resource);

        } else if (resource.idType == IdType.INT_ARRAY && resource.type == RType.STYLEABLE) {
          Map<RDotTxtEntry, String> styleableResourcesMap =
              getStyleableResources(resourceToIdValuesMap, linesInSymbolsFile, resource, index + 1);

          for (RDotTxtEntry styleableResource : styleableResourcesMap.keySet()) {
            resourceToIdValuesMap.put(styleableResource, styleableResource);
          }

          // int[] styleable entry is not added to the cache as
          // the number of child can differ in dependent libraries
          resource =
              resource.copyWithNewIdValue(
                  String.format(
                      "{ %s }",
                      Joiner.on(RDotTxtEntry.INT_ARRAY_SEPARATOR)
                          .join(styleableResourcesMap.values())));
        } else {
          Preconditions.checkNotNull(enumerator);
          resource = resource.copyWithNewIdValue(String.format("0x%08x", enumerator.next()));

          // Add resource to cache so that the id value is consistent across all R.txt
          resourceToIdValuesMap.put(resource, resource);
        }

        if (bannedDuplicateResourceTypes.contains(resource.type)) {
          bannedDuplicateResourceToSymbolsFiles.put(resource, symbolsFile);
        }
        rDotJavaPackageToSymbolsFiles.put(packageName, resource);
      }
    }

    // Find any "overridden" resources that were actually new resources and add them.
    Map<RDotTxtEntry, String> finalFinalIds = finalIds;
    overrides.ifPresent(
        ovr ->
            ovr.forEach(
                (pkg, resource) -> {
                  Preconditions.checkNotNull(finalFinalIds);
                  if (!rDotJavaPackageToSymbolsFiles.containsEntry(pkg, resource)) {
                    String realId = finalFinalIds.get(resource);
                    Preconditions.checkState(
                        realId != null,
                        "ID for resource created by filtering is not present in R.txt: %s",
                        resource);
                    rDotJavaPackageToSymbolsFiles.put(pkg, resource.copyWithNewIdValue(realId));
                  }
                }));

    StringBuilder duplicateResourcesMessage = new StringBuilder();
    for (Map.Entry<RDotTxtEntry, Collection<Path>> resourceAndSymbolsFiles :
        bannedDuplicateResourceToSymbolsFiles.asMap().entrySet()) {
      Collection<Path> paths = resourceAndSymbolsFiles.getValue();
      RDotTxtEntry resource = resourceAndSymbolsFiles.getKey();
      if (paths.size() > 1 && !duplicateIsWhitelisted(resource, duplicateResourceWhitelist)) {
        duplicateResourcesMessage.append(
            String.format(
                "Resource '%s' (%s) is duplicated across: ", resource.name, resource.type));
        List<SourcePath> resourceDirs = new ArrayList<>(paths.size());
        for (Path path : paths) {
          SourcePath res = symbolsFileToResourceDeps.get(path).getRes();
          if (res != null) {
            resourceDirs.add(res);
          }
        }
        duplicateResourcesMessage.append(Joiner.on(", ").join(resourceDirs));
        duplicateResourcesMessage.append("\n");
      }
    }

    if (duplicateResourcesMessage.length() > 0) {
      throw new DuplicateResourceException(duplicateResourcesMessage.toString());
    }

    return rDotJavaPackageToSymbolsFiles;
  }

  private static boolean duplicateIsWhitelisted(RDotTxtEntry resource, Set<String> whitelist) {
    return whitelist.contains(resource.type.toString().toLowerCase() + " " + resource.name);
  }

  private static Map<RDotTxtEntry, String> getStyleableResources(
      Map<RDotTxtEntry, RDotTxtEntry> resourceToIdValuesMap,
      List<RDotTxtEntry> linesInSymbolsFile,
      RDotTxtEntry resource,
      int index) {

    Map<RDotTxtEntry, String> styleableResourceMap = new LinkedHashMap<>();
    List<String> givenResourceIds = null;

    for (int styleableIndex = 0;
        styleableIndex + index < linesInSymbolsFile.size();
        styleableIndex++) {

      RDotTxtEntry styleableResource =
          linesInSymbolsFile.get(styleableIndex + index).copyWithNewParent(resource.name);

      String styleablePrefix = resource.name + "_";

      if (styleableResource.idType == IdType.INT
          && styleableResource.type == RType.STYLEABLE
          && styleableResource.name.startsWith(styleablePrefix)) {

        String attrName = styleableResource.name.substring(styleablePrefix.length());

        RDotTxtEntry attrResource = new RDotTxtEntry(IdType.INT, RType.ATTR, attrName, "");

        if (resourceToIdValuesMap.containsKey(attrResource)) {
          attrResource = resourceToIdValuesMap.get(attrResource);
        }

        if (Strings.isNullOrEmpty(attrResource.idValue)) {
          String attrIdValue;
          if (givenResourceIds == null) {
            if (resource.idValue.startsWith("{") && resource.idValue.endsWith("}")) {
              givenResourceIds =
                  Arrays.stream(
                          resource
                              .idValue
                              .substring(1, resource.idValue.length() - 1)
                              .split(RDotTxtEntry.INT_ARRAY_SEPARATOR))
                      .map(String::trim)
                      .filter(s -> s.length() > 0)
                      .collect(Collectors.toList());
            } else {
              givenResourceIds = new ArrayList<>();
            }
          }

          int styleableResourceIndex = Integer.parseInt(styleableResource.idValue);
          if (styleableResourceIndex < givenResourceIds.size()) {

            // These are attributes coming from android SDK -- `android_*`
            attrIdValue = givenResourceIds.get(styleableResourceIndex);
          } else {

            // If not value is found just put the index.
            attrIdValue = String.valueOf(styleableIndex);
          }

          // Add resource to cache so that the id value is consistent across all R.txt
          attrResource = attrResource.copyWithNewIdValue(attrIdValue);
          resourceToIdValuesMap.put(attrResource, attrResource);
        }

        styleableResourceMap.put(
            styleableResource.copyWithNewIdValue(String.valueOf(styleableIndex)),
            attrResource.idValue);

      } else {
        break;
      }
    }

    return styleableResourceMap;
  }

  private static RDotTxtEntry parseEntryOrThrow(String line) {
    Optional<RDotTxtEntry> parsedEntry = RDotTxtEntry.parse(line);
    Preconditions.checkState(parsedEntry.isPresent(), "Should be able to match '%s'.", line);

    return parsedEntry.get();
  }

  @Override
  public String getShortName() {
    return "android-res-merge";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    List<String> resources =
        androidResourceDeps
            .stream()
            .map(Object::toString)
            .sorted(natural())
            .collect(Collectors.toList());
    return getShortName() + " " + Joiner.on(' ').join(resources);
  }

  protected Path getPathToRDotJava(String rDotJavaPackage) {
    return outputDir
        .resolve(rDotJavaPackage.replace('.', '/'))
        .resolve(String.format("%s.java", rName));
  }

  private static class IntEnumerator {
    private int value;

    IntEnumerator(int start) {
      value = start;
    }

    public int next() {
      Preconditions.checkState(value < Integer.MAX_VALUE, "Stop goofing off");
      return value++;
    }
  }

  @VisibleForTesting
  public static class DuplicateResourceException extends Exception {
    DuplicateResourceException(String messageFormat, Object... args) {
      super(String.format(messageFormat, args));
    }
  }

  @VisibleForTesting
  public EnumSet<RType> getBannedDuplicateResourceTypes() {
    return bannedDuplicateResourceTypes;
  }
}

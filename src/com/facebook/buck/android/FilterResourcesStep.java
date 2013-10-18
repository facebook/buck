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

import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.DefaultFilteredDirectoryCopier;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.FilteredDirectoryCopier;
import com.facebook.buck.util.Filters;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * This {@link com.facebook.buck.step.Step} copies {@code res} directories to a different location,
 * while filtering out certain resources.
 */
public class FilterResourcesStep implements Step {

  /**
   * We use this to compute scaling factors between different densities.
   */
  private static Map<String, Double> DPI_VALUES = ImmutableMap.of(
      "mdpi", 160.0,
      "hdpi", 240.0,
      "xhdpi", 320.0);

  @VisibleForTesting
  static final Pattern DRAWABLE_PATH_PATTERN = Pattern.compile(
      ".*drawable.*/.*(png|jpg|jpeg|gif)", Pattern.CASE_INSENSITIVE);

  @VisibleForTesting
  static final Pattern NON_ENGLISH_STRING_PATH = Pattern.compile(
      "(\\b|.*/)res/values-.+/strings.xml", Pattern.CASE_INSENSITIVE);

  private final ImmutableBiMap<String, String> inResDirToOutResDirMap;
  private final boolean filterDrawables;
  private final boolean filterStrings;
  private final FilteredDirectoryCopier filteredDirectoryCopier;
  @Nullable
  private final String resourceFilter;
  @Nullable
  private final DrawableFinder drawableFinder;
  @Nullable
  private final ImageScaler imageScaler;
  private final ImmutableSet.Builder<String> nonEnglishStringFilesBuilder;

  /**
   * Creates a command that filters a specified set of directories.
   * @param inResDirToOutResDirMap set of {@code res} directories to filter
   * @param filterDrawables whether to filter drawables (images)
   * @param filterStrings whether to filter non-english strings
   * @param filteredDirectoryCopier refer {@link FilteredDirectoryCopier}
   *
   * @param resourceFilter filtering to perform (currently based on density; allowed values are
   *     {@code "mdpi"}, {@code "hdpi"} and {@code "xhdpi"}). Only applicable if filterDrawables
   *     is true
   * @param drawableFinder refer {@link DrawableFinder}. Only applicable if filterDrawables is true.
   * @param imageScaler if not null, use the {@link ImageScaler} to downscale higher-density
   *     drawables for which we weren't able to find an image file of the proper density (as opposed
   *     to allowing Android to do it at runtime). Only applicable if filterDrawables. is true.
   */
  @VisibleForTesting
  FilterResourcesStep(
      ImmutableBiMap<String, String> inResDirToOutResDirMap,
      boolean filterDrawables,
      boolean filterStrings,
      FilteredDirectoryCopier filteredDirectoryCopier,
      @Nullable String resourceFilter,
      @Nullable DrawableFinder drawableFinder,
      @Nullable ImageScaler imageScaler) {

    Preconditions.checkArgument(filterDrawables || filterStrings);
    Preconditions.checkArgument(!filterDrawables ||
        (resourceFilter != null && drawableFinder != null));
    this.inResDirToOutResDirMap = Preconditions.checkNotNull(inResDirToOutResDirMap);
    this.filterDrawables = filterDrawables;
    this.filterStrings = filterStrings;
    this.filteredDirectoryCopier = Preconditions.checkNotNull(filteredDirectoryCopier);

    this.resourceFilter = resourceFilter;
    this.drawableFinder = drawableFinder;
    this.imageScaler = imageScaler;
    this.nonEnglishStringFilesBuilder = ImmutableSet.builder();
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      return doExecute(context);
    } catch (Exception e) {
      context.logError(e, "There was an error filtering resources.");
      return 1;
    }
  }

  /**
   * @return If {@code filterStrings} is true, set containing absolute file paths to non-english
   * string files, matching NON_ENGLISH_STRING_PATH regex; else empty set.
   */
  public ImmutableSet<String> getNonEnglishStringFiles() {
    return nonEnglishStringFilesBuilder.build();
  }

  public ImmutableSet<String> getOutputResourceDirs() {
    return inResDirToOutResDirMap.values();
  }

  private int doExecute(ExecutionContext context) throws IOException {
    List<Predicate<File>> filePredicates = Lists.newArrayList();
    if (filterDrawables) {
      Set<String> drawables = drawableFinder.findDrawables(inResDirToOutResDirMap.keySet());
      filePredicates.add(Filters.createImageDensityFilter(drawables, resourceFilter));
    }

    if (filterStrings) {
      filePredicates.add(new Predicate<File>() {
        @Override
        public boolean apply(File input) {
          String inputPath = input.getAbsolutePath();
          if (NON_ENGLISH_STRING_PATH.matcher(inputPath).matches()) {
            nonEnglishStringFilesBuilder.add(inputPath);
            return false;
          }
          return true;
        }
      });
    }


    // Create filtered copies of all resource directories. These will be passed to aapt instead.
    filteredDirectoryCopier.copyDirs(inResDirToOutResDirMap, Predicates.and(filePredicates));

    // If an ImageScaler was specified, but only if it is available, try to apply it.
    if ((imageScaler != null) && imageScaler.isAvailable(context)) {
      scaleUnmatchedDrawables(context);
    }

    return 0;
  }

  @Override
  public String getShortName() {
    return "resource_filtering";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Filtering drawable and string resources.";
  }

  @VisibleForTesting
  String getResourceFilter() {
    return resourceFilter;
  }

  @VisibleForTesting
  boolean isFilterStrings() {
    return filterStrings;
  }

  @VisibleForTesting
  ImmutableBiMap<String, String> getInResDirToOutResDirMap() {
    return inResDirToOutResDirMap;
  }

  /**
   * Looks through filtered drawables for files not of the target density and replaces them with
   * scaled versions.
   * <p/>
   * Any drawables found by this step didn't have equivalents in the target density. If they are of
   * a higher density, we can replicate what Android does and downscale them at compile-time.
   */
  private void scaleUnmatchedDrawables(ExecutionContext context) throws IOException {
    ProjectFilesystem filesystem = context.getProjectFilesystem();

    // Go over all the images that remain after filtering.
    for (String drawable : drawableFinder.findDrawables(inResDirToOutResDirMap.values())) {
      File drawableFile = filesystem.getFileForRelativePath(drawable);

      if (drawable.endsWith(".9.png")) {
        // Skip nine-patch for now.
        continue;
      }

      Filters.Qualifiers qualifiers = new Filters.Qualifiers(drawableFile);

      // If the image has a qualifier but it's not the right one.
      if (!qualifiers.density.equals(this.resourceFilter) && !qualifiers.density.isEmpty()) {

        // Replace density qualifier with target density using regular expression to match
        // the qualifier in the context of a path to a drawable.
        String destination = drawable.replaceFirst(
            "((?:^|/)drawable[^/]*-)" + Pattern.quote(qualifiers.density) + "(-|$|/)",
            "$1" + resourceFilter + "$2");

        double factor = DPI_VALUES.get(resourceFilter) / (DPI_VALUES.get(qualifiers.density));
        if (factor > 1.0) {
          // There is no point in up-scaling.
          continue;
        }

        // Make sure destination folder exists and perform downscaling.
        filesystem.createParentDirs(destination);
        imageScaler.scale(factor, drawable, destination, context);

        // Delete source file.
        if (!filesystem.deleteFileAtPath(drawable)) {
          throw new HumanReadableException("Cannot delete file: " + drawable);
        }

        // Delete newly-empty directories to prevent missing resources errors in apkbuilder.
        String parent = drawableFile.getParent();
        if (filesystem.listFiles(parent).length == 0 && !filesystem.deleteFileAtPath(parent)) {
          throw new HumanReadableException("Cannot delete directory: " + parent);
        }

      }
    }
  }

  public interface DrawableFinder {
    public Set<String> findDrawables(Iterable<String> dirs) throws IOException;
  }

  public static class DefaultDrawableFinder implements DrawableFinder {

    private static final DefaultDrawableFinder instance = new DefaultDrawableFinder();

    public static DefaultDrawableFinder getInstance() {
      return instance;
    }

    @Override
    public Set<String> findDrawables(Iterable<String> dirs) throws IOException {
      final ImmutableSet.Builder<String> drawableBuilder = ImmutableSet.builder();
      for (String dir : dirs) {
        new DirectoryTraversal(new File(dir)) {
          @Override
          public void visit(File file, String relativePath) {
            if (DRAWABLE_PATH_PATTERN.matcher(relativePath).matches()) {
              // The path is normalized so that the value can be matched against patterns.
              drawableBuilder.add(MorePaths.newPathInstance(file).toString());
            }
          }
        }.traverse();
      }
      return drawableBuilder.build();
    }
  }

  public interface ImageScaler {
    public boolean isAvailable(ExecutionContext context);
    public void scale(double factor, String source, String destination, ExecutionContext context);
  }

  /**
   * Implementation of {@link ImageScaler} that uses ImageMagick's {@code convert} command.
   *
   * @see <a href="http://www.imagemagick.org/script/index.php">ImageMagick</a>
   */
  public static class ImageMagickScaler implements ImageScaler {

    private static final ImageMagickScaler instance = new ImageMagickScaler();

    public static ImageMagickScaler getInstance() {
      return instance;
    }

    @Override
    public boolean isAvailable(ExecutionContext context) {
      return 0 == new BashStep("which convert").execute(context);
    }

    @Override
    public void scale(double factor, String source, String destination, ExecutionContext context) {
      Step convertStep = new BashStep(
          "convert",
          "-adaptive-resize", (int) (factor * 100) + "%",
          Escaper.escapeAsBashString(source),
          Escaper.escapeAsBashString(destination)
      );

      if (0 != convertStep.execute(context)) {
        throw new HumanReadableException("Cannot scale " + source + " to " + destination);
      }
    }
  }

  public static class ResourceFilter {

    public ResourceFilter(List<String> resourceFilter) {
      this.filter = ImmutableList.copyOf(resourceFilter);
    }

    private final List<String> filter;

    public boolean shouldDownscale() {
      return filter.contains("downscale");
    }

    @Nullable
    public String getDensity() {
      String density = null;
      for (String option : filter) {
        if (Filters.ORDERING.containsKey(option)) {
          if (density == null) {
            density = option;
          } else {
            throw new HumanReadableException("Multiple target densities not supported yet.");
          }
        }
      }
      return density;
    }

    public boolean isEnabled() {
      return getDensity() != null;
    }

    public String getDescription() {
      return filter.toString();
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private ImmutableBiMap<String, String> inResDirToOutResDirMap;
    private ResourceFilter resourceFilter;
    private boolean filterStrings = false;

    private Builder() {
    }

    public Builder setInResToOutResDirMap(ImmutableBiMap<String, String> inResDirToOutResDirMap) {
      this.inResDirToOutResDirMap = inResDirToOutResDirMap;
      return this;
    }

    public Builder setResourceFilter(ResourceFilter resourceFilter) {
      this.resourceFilter = resourceFilter;
      return this;
    }

    public Builder enableStringsFilter() {
      this.filterStrings = true;
      return this;
    }

    public FilterResourcesStep build() {
      return new FilterResourcesStep(
          inResDirToOutResDirMap,
          resourceFilter.isEnabled(),
          filterStrings,
          DefaultFilteredDirectoryCopier.getInstance(),
          resourceFilter.getDensity(),
          DefaultDrawableFinder.getInstance(),
          resourceFilter.shouldDownscale() ? ImageMagickScaler.getInstance() : null);
    }
  }
}

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
import com.facebook.buck.util.Filters.Density;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * This {@link com.facebook.buck.step.Step} copies {@code res} directories to a different location,
 * while filtering out certain resources.
 */
public class FilterResourcesStep implements Step {

  private static final Pattern DRAWABLE_PATH_PATTERN = Pattern.compile(
      ".*drawable.*/.*(png|jpg|jpeg|gif|webp)", Pattern.CASE_INSENSITIVE);
  // Android doesn't scale these, so we don't need to scale or filter them either.
  private static final Pattern DRAWABLE_EXCLUDE_PATTERN = Pattern.compile(
      ".*-nodpi.*", Pattern.CASE_INSENSITIVE);


  @VisibleForTesting
  static final Pattern NON_ENGLISH_STRING_PATH = Pattern.compile(
      "(\\b|.*/)res/values-.+/strings.xml", Pattern.CASE_INSENSITIVE);

  private final ImmutableBiMap<String, String> inResDirToOutResDirMap;
  private final boolean filterDrawables;
  private final boolean filterStrings;
  private final FilteredDirectoryCopier filteredDirectoryCopier;
  @Nullable
  private final Set<Filters.Density> targetDensities;
  @Nullable
  private final DrawableFinder drawableFinder;
  @Nullable
  private final ImageScaler imageScaler;
  private final ImmutableSet.Builder<Path> nonEnglishStringFilesBuilder;

  /**
   * Creates a command that filters a specified set of directories.
   * @param inResDirToOutResDirMap set of {@code res} directories to filter
   * @param filterDrawables whether to filter drawables (images)
   * @param filterStrings whether to filter non-english strings
   * @param filteredDirectoryCopier refer {@link FilteredDirectoryCopier}
   *
   * @param targetDensities densities we're interested in keeping (e.g. {@code mdpi}, {@code hdpi}
   *     etc.) Only applicable if filterDrawables is true
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
      @Nullable Set<Filters.Density> targetDensities,
      @Nullable DrawableFinder drawableFinder,
      @Nullable ImageScaler imageScaler) {

    Preconditions.checkArgument(filterDrawables || filterStrings);
    Preconditions.checkArgument(!filterDrawables ||
        (targetDensities != null && drawableFinder != null));
    this.inResDirToOutResDirMap = Preconditions.checkNotNull(inResDirToOutResDirMap);
    this.filterDrawables = filterDrawables;
    this.filterStrings = filterStrings;
    this.filteredDirectoryCopier = Preconditions.checkNotNull(filteredDirectoryCopier);
    this.targetDensities = targetDensities;
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
  public ImmutableSet<Path> getNonEnglishStringFiles() {
    return nonEnglishStringFilesBuilder.build();
  }

  public ImmutableSet<String> getOutputResourceDirs() {
    return inResDirToOutResDirMap.values();
  }

  private int doExecute(ExecutionContext context) throws IOException {
    List<Predicate<File>> filePredicates = Lists.newArrayList();

    final boolean canDownscale = imageScaler != null && imageScaler.isAvailable(context);

    if (filterDrawables) {
      Set<String> drawables = drawableFinder.findDrawables(inResDirToOutResDirMap.keySet());
      filePredicates.add(
          Filters.createImageDensityFilter(drawables, targetDensities, canDownscale));
    }

    if (filterStrings) {
      final ProjectFilesystem filesystem = context.getProjectFilesystem();
      filePredicates.add(new Predicate<File>() {
        @Override
        public boolean apply(File input) {
          // TODO(user): Change the predicate to accept a relative Path instead of File.
          String inputPath = input.getAbsolutePath();
          if (NON_ENGLISH_STRING_PATH.matcher(inputPath).matches()) {
            Path pathRelativeToProjectRoot = filesystem.getRootPath().toAbsolutePath().normalize()
                .relativize(Paths.get(inputPath));
            nonEnglishStringFilesBuilder.add(pathRelativeToProjectRoot);
            return false;
          }
          return true;
        }
      });
    }

    // Create filtered copies of all resource directories. These will be passed to aapt instead.
    filteredDirectoryCopier.copyDirs(inResDirToOutResDirMap, Predicates.and(filePredicates));

    // If an ImageScaler was specified, but only if it is available, try to apply it.
    if (canDownscale && filterDrawables) {
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
  Set<Filters.Density> getTargetDensities() {
    return targetDensities;
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
    Filters.Density targetDensity = Filters.Density.ORDERING.max(targetDensities);

    // Go over all the images that remain after filtering.
    for (String drawable : drawableFinder.findDrawables(inResDirToOutResDirMap.values())) {
      File drawableFile = filesystem.getFileForRelativePath(drawable);

      if (drawable.endsWith(".9.png")) {
        // Skip nine-patch for now.
        continue;
      }

      Filters.Qualifiers qualifiers = new Filters.Qualifiers(drawableFile);
      Filters.Density density = qualifiers.density;

      // If the image has a qualifier but it's not the right one.
      if (!targetDensities.contains(density)) {

        // Replace density qualifier with target density using regular expression to match
        // the qualifier in the context of a path to a drawable.
        String fromDensity = (density == Density.NO_QUALIFIER ? "" : "-") + density.toString();
        String destination = drawable.replaceFirst(
            "((?:^|/)drawable[^/]*)" + Pattern.quote(fromDensity) + "(-|$|/)",
            "$1-" + targetDensity + "$2");

        double factor = targetDensity.value() / density.value();
        if (factor >= 1.0) {
          // There is no point in up-scaling, or converting between drawable and drawable-mdpi.
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
            if (DRAWABLE_PATH_PATTERN.matcher(relativePath).matches() &&
                !DRAWABLE_EXCLUDE_PATTERN.matcher(relativePath).matches()) {
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

  /**
   * Helper class for interpreting the resource_filter argument to android_binary().
   */
  public static class ResourceFilter {

    static final ResourceFilter EMPTY_FILTER = new ResourceFilter(ImmutableList.<String>of());

    private final Set<String> filter;
    private final Set<Filters.Density> densities;
    private final boolean downscale;

    public ResourceFilter(List<String> resourceFilter) {
      this.filter = ImmutableSet.copyOf(resourceFilter);
      this.densities = Sets.newHashSet();

      boolean downscale = false;
      for (String component : filter) {
        if ("downscale".equals(component)) {
          downscale = true;
        } else {
          densities.add(Filters.Density.from(component));
        }
      }

      this.downscale = downscale;
    }

    public boolean shouldDownscale() {
      return isEnabled() && downscale;
    }

    @Nullable
    public Set<Filters.Density> getDensities() {
      return densities;
    }

    public boolean isEnabled() {
      return !densities.isEmpty();
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
          resourceFilter.getDensities(),
          DefaultDrawableFinder.getInstance(),
          resourceFilter.shouldDownscale() ? ImageMagickScaler.getInstance() : null);
    }
  }
}

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
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.FilteredDirectoryCopier;
import com.facebook.buck.util.Filters;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

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

  private static final Pattern DRAWABLE_PATH_PATTERN = Pattern.compile(
      ".*drawable.*/.*(png|jpg|jpeg|gif)", Pattern.CASE_INSENSITIVE);

  private final File baseDestination;
  private final String resourceFilter;
  private final FilteredDirectoryCopier filteredDirectoryCopier;
  private final DrawableFinder drawableFinder;
  private final ImmutableBiMap<String, String> originalToFiltered;
  @Nullable
  private final ImageScaler imageScaler;

  /**
   * Creates a command that filters a specified set of directories.
   * @param resDirectories set of {@code res} directories to filter
   * @param baseDestination destination directory, where copies of these directories will be placed
   *     (will be created if necessary)
   * @param resourceFilter filtering to perform (currently based on density; allowed values are
   *     {@code "mdpi"}, {@code "hdpi"} and {@code "xhdpi"})
   * @param imageScaler if not null, use the {@link ImageScaler} to downscale higher-density
   *     drawables for which we weren't able to find an image file of the proper density (as opposed
   *     to allowing Android to do it at runtime)
   */
  public FilterResourcesStep(
      Set<String> resDirectories,
      File baseDestination,
      String resourceFilter,
      FilteredDirectoryCopier filteredDirectoryCopier,
      DrawableFinder drawableFinder,
      @Nullable ImageScaler imageScaler) {
    this.baseDestination = Preconditions.checkNotNull(baseDestination);
    this.resourceFilter = Preconditions.checkNotNull(resourceFilter);
    this.filteredDirectoryCopier = Preconditions.checkNotNull(filteredDirectoryCopier);
    this.drawableFinder = Preconditions.checkNotNull(drawableFinder);
    this.originalToFiltered = assignDestinations(
        Preconditions.checkNotNull(resDirectories),
        Preconditions.checkNotNull(baseDestination));
    this.imageScaler = imageScaler;
  }

  private static ImmutableBiMap<String, String> assignDestinations(Set<String> sources, File base) {
    ImmutableBiMap.Builder<String, String> builder = ImmutableBiMap.builder();
    int count = 0;
    for (String source : sources) {
      builder.put(source, new File(base, String.valueOf(count++)).getPath());
    }
    return builder.build();
  }

  /**
   * Returns drop-in set of resource directories for the rest of the build (e.g. {@code aapt}).
   * @return set of directories which, after running {@link #execute(ExecutionContext)},
   *     will contain filtered copies of the resource directories passed on construction.
   */
  public ImmutableSet<String> getFilteredResourceDirectories() {
    // getFilteredResourceDirectories() yields matching destination directories in the same order
    // as the sources, which is relevant to aapt. Adding
    //   return new TreeSet<String>(destinations);
    // here would yield a broken app
    return originalToFiltered.values();
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      return doExecute(context);
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
  }

  private int doExecute(ExecutionContext context) throws IOException {
    // Get list of candidate drawables.
    Set<String> drawables = drawableFinder.findDrawables(originalToFiltered.keySet());

    // Create a filter that removes drawables not of desired density.
    Predicate<File> densityFilter = Filters.createImageDensityFilter(drawables, resourceFilter);
    // Create filtered copies of all resource directories. These will be passed to aapt instead.
    filteredDirectoryCopier.copyDirs(originalToFiltered,
        densityFilter);

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
    return String.format(
        "Filtering %d resource directories into: %s",
        originalToFiltered.size(),
        baseDestination);
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
    for (String drawable : drawableFinder.findDrawables(getFilteredResourceDirectories())) {
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
              drawableBuilder.add(file.getPath());
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

}

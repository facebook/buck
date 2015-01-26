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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultFilteredDirectoryCopier;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.FilteredDirectoryCopier;
import com.facebook.buck.util.Filters;
import com.facebook.buck.util.Filters.Density;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
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

  private static final Logger LOG = Logger.get(FilterResourcesStep.class);

  @VisibleForTesting
  static final Pattern NON_ENGLISH_STRING_PATH = Pattern.compile(
      "(\\b|.*/)res/values-.+/strings.xml", Pattern.CASE_INSENSITIVE);

  @VisibleForTesting
  static final Pattern VALUES_DIR_PATTERN = Pattern.compile(
      "\\b|.*/res/values-([a-z]{2})(?:-r([A-Z]{2}))*/.*");

  private final ImmutableBiMap<Path, Path> inResDirToOutResDirMap;
  private final boolean filterDrawables;
  private final boolean filterStrings;
  private final ImmutableSet<Path> whitelistedStringDirs;
  private final ImmutableSet<String> locales;
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
   * @param whitelistedStringDirs set of directories containing string resource files that must not
   *     be filtered out.
   * @param filteredDirectoryCopier refer {@link FilteredDirectoryCopier}
   * @param targetDensities densities we're interested in keeping (e.g. {@code mdpi}, {@code hdpi}
   *     etc.) Only applicable if filterDrawables is true
   * @param drawableFinder refer {@link DrawableFinder}. Only applicable if filterDrawables is true.
   * @param imageScaler if not null, use the {@link ImageScaler} to downscale higher-density
   *     drawables for which we weren't able to find an image file of the proper density (as opposed
   *     to allowing Android to do it at runtime). Only applicable if filterDrawables. is true.
   */
  @VisibleForTesting
  FilterResourcesStep(
      ImmutableBiMap<Path, Path> inResDirToOutResDirMap,
      boolean filterDrawables,
      boolean filterStrings,
      ImmutableSet<Path> whitelistedStringDirs,
      ImmutableSet<String> locales,
      FilteredDirectoryCopier filteredDirectoryCopier,
      @Nullable Set<Filters.Density> targetDensities,
      @Nullable DrawableFinder drawableFinder,
      @Nullable ImageScaler imageScaler) {

    Preconditions.checkArgument(filterDrawables || filterStrings || !locales.isEmpty());
    Preconditions.checkArgument(!filterDrawables ||
        (targetDensities != null && drawableFinder != null));
    this.inResDirToOutResDirMap = inResDirToOutResDirMap;
    this.filterDrawables = filterDrawables;
    this.filterStrings = filterStrings;
    this.whitelistedStringDirs = whitelistedStringDirs;
    this.locales = locales;
    this.filteredDirectoryCopier = filteredDirectoryCopier;
    this.targetDensities = targetDensities;
    this.drawableFinder = drawableFinder;
    this.imageScaler = imageScaler;
    this.nonEnglishStringFilesBuilder = ImmutableSet.builder();
    LOG.info(
        "FilterResourcesStep: filterDrawables: %s; filterStrings: %s",
        filterDrawables,
        filterStrings);
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

  private int doExecute(ExecutionContext context) throws IOException, InterruptedException {
    List<Predicate<Path>> pathPredicates = Lists.newArrayList();

    boolean canDownscale = imageScaler != null && imageScaler.isAvailable(context);
    LOG.info(
        "FilterResourcesStep: canDownscale: %s. imageScalar non-null: %s.",
        canDownscale,
        imageScaler != null);

    if (filterDrawables) {
      Preconditions.checkNotNull(drawableFinder);
      Set<Path> drawables = drawableFinder.findDrawables(
          inResDirToOutResDirMap.keySet(),
          context.getProjectFilesystem());
      pathPredicates.add(
          Filters.createImageDensityFilter(drawables, targetDensities, canDownscale));
    }

    if (!locales.isEmpty()) {
      pathPredicates.add(
          new Predicate<Path>() {
            @Override
            public boolean apply(Path input) {
              Matcher matcher = VALUES_DIR_PATTERN.matcher(input.toString());
              if (!matcher.matches()) {
                return true;
              }
              String locale = matcher.group(1);
              if (matcher.group(2) != null) {
                locale += "_" + matcher.group(2);
              }
              return locales.contains(locale);
            }
          });
    }

    if (filterStrings) {
      pathPredicates.add(
          new Predicate<Path>() {
            @Override
            public boolean apply(Path pathRelativeToProjectRoot) {
              if (!NON_ENGLISH_STRING_PATH.matcher(pathRelativeToProjectRoot.toString())
                  .matches()) {
                return true;
              }
              for (Path whitelistedStringDir : whitelistedStringDirs) {
                if (pathRelativeToProjectRoot.startsWith(whitelistedStringDir)) {
                  return true;
                }
              }
              nonEnglishStringFilesBuilder.add(pathRelativeToProjectRoot);
              return false;
            }
          });
    }

    // Create filtered copies of all resource directories. These will be passed to aapt instead.
    filteredDirectoryCopier.copyDirs(
        context.getProjectFilesystem(),
        inResDirToOutResDirMap,
        Predicates.and(pathPredicates));

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

  /**
   * Looks through filtered drawables for files not of the target density and replaces them with
   * scaled versions.
   * <p/>
   * Any drawables found by this step didn't have equivalents in the target density. If they are of
   * a higher density, we can replicate what Android does and downscale them at compile-time.
   */
  private void scaleUnmatchedDrawables(ExecutionContext context)
      throws IOException, InterruptedException {
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    Filters.Density targetDensity = Filters.Density.ORDERING.max(targetDensities);

    // Go over all the images that remain after filtering.
    Preconditions.checkNotNull(drawableFinder);
    Collection<Path> drawables = drawableFinder.findDrawables(
        inResDirToOutResDirMap.values(),
        context.getProjectFilesystem());
    for (Path drawable : drawables) {
      if (drawable.toString().endsWith(".9.png")) {
        // Skip nine-patch for now.
        continue;
      }

      Filters.Qualifiers qualifiers = new Filters.Qualifiers(drawable);
      Filters.Density density = qualifiers.density;

      // If the image has a qualifier but it's not the right one.
      Preconditions.checkNotNull(targetDensities);
      if (!targetDensities.contains(density)) {

        // Replace density qualifier with target density using regular expression to match
        // the qualifier in the context of a path to a drawable.
        String fromDensity = (density == Density.NO_QUALIFIER ? "" : "-") + density.toString();
        Path destination = Paths.get(drawable.toString().replaceFirst(
            "((?:^|/)drawable[^/]*)" + Pattern.quote(fromDensity) + "(-|$|/)",
            "$1-" + targetDensity + "$2"));

        double factor = targetDensity.value() / density.value();
        if (factor >= 1.0) {
          // There is no point in up-scaling, or converting between drawable and drawable-mdpi.
          continue;
        }

        // Make sure destination folder exists and perform downscaling.
        filesystem.createParentDirs(destination);
        Preconditions.checkNotNull(imageScaler);
        imageScaler.scale(factor, drawable, destination, context);

        // Delete source file.
        if (!filesystem.deleteFileAtPath(drawable)) {
          throw new HumanReadableException("Cannot delete file: " + drawable);
        }

        // Delete newly-empty directories to prevent missing resources errors in apkbuilder.
        Path parent = drawable.getParent();
        if (filesystem.listFiles(parent).length == 0 && !filesystem.deleteFileAtPath(parent)) {
          throw new HumanReadableException("Cannot delete directory: " + parent);
        }

      }
    }
  }

  public interface DrawableFinder {
    public Set<Path> findDrawables(Collection<Path> dirs, ProjectFilesystem filesystem)
        throws IOException;
  }

  public static class DefaultDrawableFinder implements DrawableFinder {

    private static final DefaultDrawableFinder instance = new DefaultDrawableFinder();

    public static DefaultDrawableFinder getInstance() {
      return instance;
    }

    @Override
    public Set<Path> findDrawables(Collection<Path> dirs, ProjectFilesystem filesystem)
        throws IOException {
      final ImmutableSet.Builder<Path> drawableBuilder = ImmutableSet.builder();
      for (Path dir : dirs) {
        filesystem.walkRelativeFileTree(dir, new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                if (DRAWABLE_PATH_PATTERN.matcher(path.toString()).matches() &&
                    !DRAWABLE_EXCLUDE_PATTERN.matcher(path.toString()).matches()) {
                  // The path is normalized so that the value can be matched against patterns.
                  drawableBuilder.add(path);
                }
                return FileVisitResult.CONTINUE;
              }
            });
      }
      return drawableBuilder.build();
    }
  }

  public interface ImageScaler {
    public boolean isAvailable(ExecutionContext context) throws IOException, InterruptedException;
    public void scale(double factor, Path source, Path destination, ExecutionContext context)
        throws IOException, InterruptedException;
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

    private ExecutionContext getContextWithSilentConsole(ExecutionContext context) {
      // Using the normal console results in the super console freezing.
      Console console = context.getConsole();
      return ExecutionContext.builder()
          .setExecutionContext(context)
          .setConsole(new Console(
              Verbosity.SILENT,
              console.getStdOut(),
              console.getStdErr(),
              console.getAnsi()
          ))
          .build();
    }

    @Override
    public boolean isAvailable(ExecutionContext context) throws IOException, InterruptedException {
      try (ExecutionContext silentContext = getContextWithSilentConsole(context)) {
        return 0 == new BashStep("which convert").execute(silentContext);
      }
    }

    @Override
    public void scale(double factor, Path source, Path destination, ExecutionContext context)
        throws IOException, InterruptedException {
      Step convertStep = new BashStep(
          "convert",
          "-adaptive-resize", (int) (factor * 100) + "%",
          Escaper.escapeAsBashString(source),
          Escaper.escapeAsBashString(destination));

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

    @VisibleForTesting
    Set<String> getFilter() {
      return filter;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    @Nullable
    private ImmutableBiMap<Path, Path> inResDirToOutResDirMap;
    @Nullable
    private ResourceFilter resourceFilter;
    private boolean filterStrings = false;
    private ImmutableSet<Path> whitelistedStringDirs = ImmutableSet.of();
    private ImmutableSet<String> locales = ImmutableSet.of();

    private Builder() {
    }

    public Builder setInResToOutResDirMap(ImmutableBiMap<Path, Path> inResDirToOutResDirMap) {
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

    public Builder setWhitelistedStringDirs(ImmutableSet<Path> whitelistedStringDirs) {
      this.whitelistedStringDirs = whitelistedStringDirs;
      return this;
    }

    public Builder setLocales(ImmutableSet<String> locales) {
      this.locales = locales;
      return this;
    }

    public FilterResourcesStep build() {
      Preconditions.checkNotNull(resourceFilter);
      LOG.info("FilterResourcesStep.Builder: resource filter: %s", resourceFilter);
      Preconditions.checkNotNull(inResDirToOutResDirMap);
      return new FilterResourcesStep(
          inResDirToOutResDirMap,
          resourceFilter.isEnabled(),
          filterStrings,
          whitelistedStringDirs,
          locales,
          DefaultFilteredDirectoryCopier.getInstance(),
          resourceFilter.getDensities(),
          DefaultDrawableFinder.getInstance(),
          resourceFilter.shouldDownscale() ? ImageMagickScaler.getInstance() : null);
    }
  }
}

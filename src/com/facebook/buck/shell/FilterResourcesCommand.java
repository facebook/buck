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

package com.facebook.buck.shell;

import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.FilteredDirectoryCopier;
import com.facebook.buck.util.Filters;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * This {@link Command} copies {@code res} directories to a different location, while filtering out
 * certain resources.
 */
public class FilterResourcesCommand implements Command {

  private static final Pattern DRAWABLE_PATH_PATTERN = Pattern.compile(
      ".*drawable.*/.*(png|jpg|jpeg|gif)", Pattern.CASE_INSENSITIVE);

  private final File baseDestination;
  private final String resourceFilter;
  private final FilteredDirectoryCopier filteredDirectoryCopier;
  private final DrawableFinder drawableFinder;
  private final ImmutableBiMap<String, String> originalToFiltered;

  /**
   * Creates a command that filters a specified set of directories.
   * @param resDirectories set of {@code res} directories to filter
   * @param baseDestination destination directory, where copies of these directories will be placed
   *     (will be created if necessary)
   * @param resourceFilter filtering to perform (currently based on density; allowed values are
   *     {@code "mdpi"}, {@code "hdpi"} and {@code "xhdpi"})
   */
  public FilterResourcesCommand(
      Set<String> resDirectories,
      File baseDestination,
      String resourceFilter,
      FilteredDirectoryCopier filteredDirectoryCopier,
      DrawableFinder drawableFinder) {
    this.baseDestination = Preconditions.checkNotNull(baseDestination);
    this.resourceFilter = Preconditions.checkNotNull(resourceFilter);
    this.filteredDirectoryCopier = Preconditions.checkNotNull(filteredDirectoryCopier);
    this.drawableFinder = Preconditions.checkNotNull(drawableFinder);
    this.originalToFiltered = assignDestinations(
        Preconditions.checkNotNull(resDirectories),
        Preconditions.checkNotNull(baseDestination));
  }

  private static ImmutableBiMap<String, String> assignDestinations(Set<String> sources, File base) {
    ImmutableBiMap.Builder<String, String> builder = ImmutableBiMap.builder();
    int count = 0;
    for (String source : sources) {
      builder.put(source, new File(base, String.valueOf(count++)).getAbsolutePath());
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
    // Get list of candidate drawables.
    Set<String> drawables = drawableFinder.findDrawables(originalToFiltered.keySet());
    // Create a filter that removes drawables not of desired density.
    Predicate<File> densityFilter = Filters.createImageDensityFilter(drawables, resourceFilter);
    // Create filtered copies of all resource directories. These will be passed to aapt instead.
    filteredDirectoryCopier.copyDirs(originalToFiltered, densityFilter);
    return 0;
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return "resource filtering";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "Filtering %d resource directories into: %s",
        originalToFiltered.size(),
        baseDestination);
  }

  public interface DrawableFinder {
    public Set<String> findDrawables(Iterable<String> dirs);
  }

  public static class DefaultDrawableFinder implements DrawableFinder {

    private static final DefaultDrawableFinder instance = new DefaultDrawableFinder();

    public static DefaultDrawableFinder getInstance() {
      return instance;
    }

    @Override
    public Set<String> findDrawables(Iterable<String> dirs) {
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
}

package com.facebook.buck.util;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableMultiset;
import java.io.IOException;
import java.nio.file.Path;

public class PathHelper {

  /**
   * Flatmap a list of files + directories into a list of files, by traversing all directories.
   * @param filesystem
   * @param sourcePaths
   * @return The new list with files only
   */
  public static ImmutableSet<Path> flatmapDirectories(ProjectFilesystem filesystem, ImmutableSet<Path> sourcePaths)
      throws IOException {
    ImmutableMultiset.Builder<Path> builder = new ImmutableMultiset.Builder<>();
    for (Path path : sourcePaths) {
      builder.addAll(getSourcesInPath(filesystem, path));
    }
    return builder.build().elementSet();
  }

  /**
   * Traverse this directory and return a list of files in it, if it is a directory, or itself,
   * if it is a file.
   * @param sourcePath
   * @return a list of files
   */
  public static ImmutableSet<Path> getSourcesInPath(ProjectFilesystem filesystem, Path sourcePath)
      throws IOException {
    ImmutableMultiset.Builder<Path> builder = new ImmutableMultiset.Builder<>();
    getInnerFiles(filesystem, sourcePath, builder);
    return builder.build().elementSet();
  }

  private static void getInnerFiles(ProjectFilesystem filesystem, Path sourcePath, ImmutableMultiset.Builder<Path> builder)
      throws IOException {
    if (filesystem.isDirectory(sourcePath)) {
      for (Path child : filesystem.getDirectoryContents(sourcePath)) {
        getInnerFiles(filesystem, child, builder);
      }
    } else {
      builder.add(sourcePath);
    }
  }
}

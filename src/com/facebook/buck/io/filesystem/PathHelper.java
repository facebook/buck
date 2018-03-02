package com.facebook.buck.io.filesystem;

import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

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
  private static ImmutableSet<Path> getSourcesInPath(ProjectFilesystem filesystem, Path sourcePath)
      throws IOException {
    ImmutableMultiset.Builder<Path> builder = new ImmutableMultiset.Builder<>();
    filesystem.walkFileTree(
        sourcePath,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
            if (!filesystem.isDirectory(path)) {
              builder.add(path);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return builder.build().elementSet();
  }
}

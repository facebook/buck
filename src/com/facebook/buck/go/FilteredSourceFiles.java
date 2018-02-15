package com.facebook.buck.go;

import com.facebook.buck.go.GoListStep.FileType;
import com.facebook.buck.model.BuildTarget;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class FilteredSourceFiles implements Iterable<Path> {
  private final List<Path> rawSrcFiles;
  private Map<Path, GoListStep> filterSteps;

  public FilteredSourceFiles(
      List<Path> rawSrcFiles,
      BuildTarget buildTarget,
      GoToolchain goToolchain,
      List<FileType> fileTypes) {
    this.rawSrcFiles = rawSrcFiles;
    initFilterSteps(buildTarget, goToolchain, fileTypes);
  }

  private void initFilterSteps(
      BuildTarget buildTarget, GoToolchain goToolchain, List<FileType> fileTypes) {
    filterSteps = new HashMap<>();
    for (Path srcFile : rawSrcFiles) {
      Path absPath = srcFile.getParent();
      if (!filterSteps.containsKey(absPath)) {
        filterSteps.put(absPath, new GoListStep(buildTarget, absPath, goToolchain, fileTypes));
      }
    }
  }

  public Collection<GoListStep> getFilterSteps() {
    return filterSteps.values();
  }

  @Override
  public Iterator<Path> iterator() {
    HashSet<Path> sourceFiles = new HashSet<>();
    for (Path srcFile : rawSrcFiles) {
      if (filterSteps.get(srcFile.getParent()).getSourceFiles().contains(srcFile)) {
        sourceFiles.add(srcFile);
      }
    }
    return sourceFiles.iterator();
  }
}

package com.facebook.buck.apple.clang;

import com.facebook.buck.model.Pair;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/**
 * VFSOverlays are used for similar purposes to headermaps, but can be used to overlay more than
 * headers on the filesystem (such as modulemaps)
 *
 * <p>This class provides support for reading and generating clang vfs overlays. No spec is
 * available but we conform to the https://clang.llvm.org/doxygen/VirtualFileSystem_8cpp_source.html
 * writer class defined in the Clang documentation.
 */
@JsonSerialize(as = VFSOverlay.class)
public class VFSOverlay {

  @SuppressWarnings("PMD.UnusedPrivateField")
  @JsonProperty
  private final int version = 0;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @JsonProperty("case-sensitive")
  private final boolean case_sensitive = false;

  @JsonProperty("roots")
  private ImmutableList<Directory> computeRoots() {
    Multimap<Path, Pair<Path, Path>> byParent = MultimapBuilder.hashKeys().hashSetValues().build();
    overlays.forEach(
        (virtual, real) -> {
          byParent.put(virtual.getParent(), new Pair<>(virtual.getFileName(), real));
        });
    return byParent
        .asMap()
        .entrySet()
        .stream()
        .map(e -> new Directory(e.getKey(), e.getValue()))
        .collect(MoreCollectors.toImmutableList());
  }

  private final ImmutableSortedMap<Path, Path> overlays;

  public VFSOverlay(ImmutableSortedMap<Path, Path> overlays) {
    this.overlays = overlays;
  }

  public String render() throws IOException {
    return ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValueAsString(this);
  }

  @JsonSerialize(as = Directory.class)
  private class Directory {

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonProperty
    private final String type = "directory";

    @JsonProperty private final Path name;

    private final Collection<Pair<Path, Path>> contents;

    @JsonProperty("contents")
    private ImmutableList<File> computeContents() {
      return this.contents
          .stream()
          .map(x -> new File(x.getFirst(), x.getSecond()))
          .collect(MoreCollectors.toImmutableList());
    }

    public Directory(Path root, Collection<Pair<Path, Path>> contents) {
      this.name = root;
      this.contents = contents;
    }
  }

  @JsonSerialize(as = File.class)
  private class File {
    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonProperty
    private final String type = "file";

    @JsonProperty private final Path name;

    @JsonProperty("external-contents")
    private final Path realPath;

    public File(Path name, Path realPath) {
      this.name = name;
      this.realPath = realPath;
    }
  }
}

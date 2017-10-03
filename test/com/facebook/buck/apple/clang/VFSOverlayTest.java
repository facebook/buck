package com.facebook.buck.apple.clang;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Test;

public class VFSOverlayTest {

  private String readTestData(String name) throws IOException {
    return new String(ByteStreams.toByteArray(getClass().getResourceAsStream(name)));
  }

  @Test
  public void testSerialization() throws IOException {
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                Paths.get("/virtual/path/module.modulemap"),
                Paths.get("/real/path/overlayed.modulemap")));
    assertEquals(readTestData("testdata/vfs_simple.yaml"), vfsOverlay.render());
  }

  @Test
  public void testTwoFiles() throws IOException {
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                Paths.get("/virtual/path/module.modulemap"),
                Paths.get("/real/path/overlayed.modulemap"),
                Paths.get("/virtual/path/umbrella.h"),
                Paths.get("/real/path/umbrella/umbrella.h")));

    assertEquals(readTestData("testdata/vfs_twofiles.yaml"), vfsOverlay.render());
  }

  @Test
  public void testTwoDirectories() throws IOException {
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                Paths.get("/virtual/path/module.modulemap"),
                Paths.get("/real/path/overlayed.modulemap"),
                Paths.get("/virtual/path-priv/umbrella.h"),
                Paths.get("/real/path/umbrella/umbrella.h")));
    assertEquals(readTestData("testdata/vfs_twodirs.yaml"), vfsOverlay.render());
  }
}

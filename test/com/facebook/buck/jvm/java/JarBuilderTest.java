/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.JarBuilder;
import com.facebook.buck.zip.JarEntryContainer;
import com.facebook.buck.zip.JarEntrySupplier;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JarBuilderTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testSortsEntriesFromAllContainers() throws IOException {
    File tempFile = temporaryFolder.newFile();
    try (TestJarEntryContainer container1 = new TestJarEntryContainer("Container1");
        TestJarEntryContainer container2 = new TestJarEntryContainer("Container2");
        TestJarEntryContainer container3 = new TestJarEntryContainer("Container3")) {
      new JarBuilder()
          .addEntryContainer(container1.addEntry("Foo", "Foo").addEntry("Bar", "Bar"))
          .addEntryContainer(
              container2.addEntry("Bird", "Bird").addEntry("Dog", "Dog").addEntry("Cat", "Cat"))
          .addEntryContainer(
              container3
                  .addEntry("A", "A")
                  .addEntry("B", "B")
                  .addEntry("C", "C")
                  .addEntry("D", "D"))
          .createJarFile(tempFile.toPath());
    }

    try (JarFile jarFile = new JarFile(tempFile)) {
      assertEquals(
          ImmutableList.of(
              "META-INF/",
              "META-INF/MANIFEST.MF",
              "A",
              "B",
              "Bar",
              "Bird",
              "C",
              "Cat",
              "D",
              "Dog",
              "Foo"),
          jarFile.stream().map(JarEntry::getName).collect(Collectors.toList()));
    }
  }

  @Test
  public void testMakesDirectoriesForEntries() throws IOException {
    File tempFile = temporaryFolder.newFile();
    JarBuilder jarBuilder = new JarBuilder();
    addEntry(jarBuilder, "foo/1.txt", "1");
    addEntry(jarBuilder, "foo/2.txt", "2");
    addEntry(jarBuilder, "foo/bar/3.txt", "3");
    jarBuilder.createJarFile(tempFile.toPath());

    try (JarFile jarFile = new JarFile(tempFile)) {
      assertEquals(
          ImmutableList.of(
              "META-INF/",
              "META-INF/MANIFEST.MF",
              "foo/",
              "foo/1.txt",
              "foo/2.txt",
              "foo/bar/",
              "foo/bar/3.txt"),
          jarFile.stream().map(JarEntry::getName).collect(Collectors.toList()));
    }
  }

  private void addEntry(JarBuilder builder, String name, String contents) {
    builder.addEntry(
        new JarEntrySupplier(
            new CustomZipEntry(name),
            "owner",
            () -> new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8))));
  }

  private static class TestJarEntryContainer implements JarEntryContainer {
    @Nullable private Manifest manifest;
    private final List<JarEntrySupplier> suppliers = new ArrayList<>();
    private final String containerName;

    private TestJarEntryContainer(String containerName) {
      this.containerName = containerName;
    }

    public TestJarEntryContainer addEntry(String name, String contents) {
      suppliers.add(
          new JarEntrySupplier(
              new CustomZipEntry(name),
              containerName,
              () -> new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8))));
      return this;
    }

    @Nullable
    @Override
    public Manifest getManifest() throws IOException {
      return manifest;
    }

    @Override
    public Stream<JarEntrySupplier> stream() throws IOException {
      return suppliers.stream();
    }

    @Override
    public void close() throws IOException {}
  }
}

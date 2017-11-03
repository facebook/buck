// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import static com.android.tools.r8.utils.FileUtils.isArchive;
import static com.android.tools.r8.utils.FileUtils.isClassFile;
import static com.android.tools.r8.utils.FileUtils.isDexFile;

import com.android.tools.r8.Resource;
import com.android.tools.r8.Resource.Origin;
import com.android.tools.r8.Resource.PathOrigin;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.shaking.FilteredClassPath;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class ProgramFileArchiveReader {

  private final Origin origin;
  private final FilteredClassPath archive;
  private boolean ignoreDexInArchive;
  private List<Resource> dexResources = null;
  private List<Resource> classResources = null;

  ProgramFileArchiveReader(FilteredClassPath archive, boolean ignoreDexInArchive) {
    origin = new PathOrigin(archive.getPath(), Origin.root());
    this.archive = archive;
    this.ignoreDexInArchive = ignoreDexInArchive;
  }

  private void readArchive() throws IOException {
    assert isArchive(archive.getPath());
    dexResources = new ArrayList<>();
    classResources = new ArrayList<>();
    try (ZipFile zipFile = new ZipFile(archive.getPath().toFile())) {
      final Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        try (InputStream stream = zipFile.getInputStream(entry)) {
          Path name = Paths.get(entry.getName());
          Origin entryOrigin = new PathOrigin(name, origin);
          if (archive.matchesFile(name)) {
            if (isDexFile(name)) {
              if (!ignoreDexInArchive) {
                Resource resource =
                    new OneShotByteResource(
                        entryOrigin,
                        ByteStreams.toByteArray(stream),
                        null);
                dexResources.add(resource);
              }
            } else if (isClassFile(name)) {
              String descriptor = DescriptorUtils.guessTypeDescriptor(name);
              Resource resource = new OneShotByteResource(
                  entryOrigin,
                  ByteStreams.toByteArray(stream),
                  Collections.singleton(descriptor));
              classResources.add(resource);
            }
          }
        }
      }
    } catch (ZipException e) {
      throw new CompilationError(
          "Zip error while reading '" + archive + "': " + e.getMessage(), e);
    }
    if (!dexResources.isEmpty() && !classResources.isEmpty()) {
      throw new CompilationError(
          "Cannot create android app from an archive '" + archive
              + "' containing both DEX and Java-bytecode content");
    }
  }

  public Collection<Resource> getDexProgramResources() throws IOException {
    if (dexResources == null) {
      readArchive();
    }
    List<Resource> result = dexResources;
    dexResources = null;
    return result;
  }

  public Collection<Resource> getClassProgramResources() throws IOException {
    if (classResources == null) {
      readArchive();
    }
    List<Resource> result = classResources;
    classResources = null;
    return result;
  }
}

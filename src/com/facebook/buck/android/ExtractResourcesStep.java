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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * A command that takes a set of JAR files and copies their resources (i.e., "non-.class files")
 * into a directory.
 * <p>
 * This is designed to produce a directory of resources that should be bundled with an APK.
 */
public class ExtractResourcesStep implements Step {

  private final ImmutableSet<String> pathsToThirdPartyJars;
  private final String extractedResourcesDir;

  public ExtractResourcesStep(Set<String> pathsToThirdPartyJars, String extractedResourcesDir) {
    this.pathsToThirdPartyJars = ImmutableSet.copyOf(pathsToThirdPartyJars);
    this.extractedResourcesDir = Preconditions.checkNotNull(extractedResourcesDir);
  }

  @Override
  public int execute(ExecutionContext context) {
    File outputDirectory = new File(extractedResourcesDir);
    for (String path : pathsToThirdPartyJars) {
      try {
        final JarFile jar = new JarFile(path);
        for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
          final JarEntry entry = entries.nextElement();
          String name = entry.getName();

          // No directories.
          if (entry.isDirectory()) {
            continue;
          }

          // No META-INF nonsense.
          if (name.startsWith("META-INF/")) {
            continue;
          }

          // No .class files.
          if (name.endsWith(".class")) {
            continue;
          }

          // No .java files, either.
          if (name.endsWith(".java")) {
            continue;
          }

          File outputFile = new File(outputDirectory, name);
          Files.createParentDirs(outputFile);
          Files.copy(new InputSupplier<InputStream>() {
            @Override
            public InputStream getInput() throws IOException {
              return jar.getInputStream(entry);
            }
          }, outputFile);
        }
      } catch (IOException e) {
        e.printStackTrace(context.getStdErr());
        return 1;
      }
    }

    return 0;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("Extracting resources into %s from the following JARs: %s",
        extractedResourcesDir,
        pathsToThirdPartyJars);
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return "resource extraction";
  }

}

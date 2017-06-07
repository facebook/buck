/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.autodeps;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.ZipFileTraversal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PrebuiltJarSymbolsFinder implements JavaSymbolsRule.SymbolsFinder {

  private static final String CLASS_SUFFIX = ".class";

  private final SourcePath binaryJar;

  PrebuiltJarSymbolsFinder(SourcePath binaryJar) {
    this.binaryJar = binaryJar;
  }

  @Override
  public Symbols extractSymbols() throws IOException {
    if (!(binaryJar instanceof PathSourcePath)) {
      return Symbols.EMPTY;
    }

    PathSourcePath sourcePath = (PathSourcePath) binaryJar;
    ProjectFilesystem filesystem = sourcePath.getFilesystem();
    Path absolutePath = filesystem.resolve(sourcePath.getRelativePath());

    final Set<String> providedSymbols = new HashSet<>();
    new ZipFileTraversal(absolutePath) {
      @Override
      public void visit(ZipFile zipFile, ZipEntry zipEntry) throws IOException {
        String name = zipEntry.getName();
        if (!name.endsWith(CLASS_SUFFIX) || name.contains("$")) {
          return;
        }

        String fullyQualifiedName =
            name.substring(0, name.length() - CLASS_SUFFIX.length()).replace('/', '.');
        providedSymbols.add(fullyQualifiedName);
      }
    }.traverse();
    return new Symbols(providedSymbols);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    if (binaryJar instanceof PathSourcePath) {
      PathSourcePath sourcePath = (PathSourcePath) binaryJar;
      sink.setReflectively("binaryJar", sourcePath);
    }
  }
}

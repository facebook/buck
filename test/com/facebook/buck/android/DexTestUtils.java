/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DexInspector;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DexTestUtils {
  private static final Pattern CANARY_NAME_REGEX =
      Pattern.compile("[^.]*.dex([0-9][0-9]+|[0-9]+_[0-9]+).Canary");

  public static void validateMetadata(Path apkPath) throws IOException {
    validateMetadata(apkPath, ImmutableSet.of());
  }

  public static void validateMetadata(Path apkPath, Set<String> modulePaths) throws IOException {
    Set<String> moduleDirs =
        ImmutableSet.<String>builder()
            .add("secondary-program-dex-jars")
            .addAll(modulePaths)
            .build();

    ZipInspector zipInspector = new ZipInspector(apkPath);
    for (String moduleDir : moduleDirs) {
      String metadataFile = "assets/" + moduleDir + "/metadata.txt";
      List<String> metadata = zipInspector.getFileContentsLines(metadataFile);
      List<DexMetadata> moduleMetadata = moduleMetadata(metadata);
      Path dexDir;
      if (moduleMetadata.get(0).dexFile.toString().startsWith("classes")
          && moduleDir.equals("secondary-program-dex-jars")) {
        dexDir = Paths.get("");
      } else {
        dexDir = Paths.get(metadataFile).getParent();
      }
      Set<Path> dexDirContents = zipInspector.getDirectoryContents(dexDir);

      // Check that dexes are sorted, redex unpacks XZS files in order listed in metadata, so the
      // ordering has to be consistent
      assertTrue(Ordering.natural().isOrdered(moduleMetadata));

      Set<String> canaryNames = new HashSet<>();
      for (DexMetadata dexMetadata : moduleMetadata) {
        String canaryName = dexMetadata.canaryName;
        // Check that canary names are unique
        assertTrue(canaryNames.add(canaryName));

        // Check that the canary name matches the pattern expected by redex, or is a dex group
        // canary.
        Matcher canaryNameMatcher = CANARY_NAME_REGEX.matcher(canaryName);
        assertTrue("Invalid canary name " + canaryName, canaryNameMatcher.matches());

        // Check that the canary name matches the indices used for the dex file naming.
        String canaryNameIndex = canaryNameMatcher.group(1);
        // If it has a preceding 0, strip it.
        if (canaryNameIndex.length() == 2 && canaryNameIndex.charAt(0) == '0') {
          canaryNameIndex = canaryNameIndex.substring(1);
        }
        assertTrue(dexMetadata.dexFile.toString().contains(canaryNameIndex));

        // metadata for XZS dexes contains temporary files, not the final merged dex, is this a bug?
        if (!DexStore.XZS.matchesPath(dexMetadata.dexFile)) {
          // Check that dex file exists
          assertThat(dexDirContents, hasItem(dexMetadata.dexFile));
          // Check that canary class exists
          DexInspector dexInspector =
              new DexInspector(apkPath, dexDir.resolve(dexMetadata.dexFile).toString());
          dexInspector.assertTypeExists(dexMetadata.getJvmName());
        }
      }
    }
  }

  public static List<DexMetadata> moduleMetadata(List<String> metadata) {
    return metadata.stream()
        .filter(line -> !line.startsWith(".") && line.length() > 0)
        .map(
            (line) -> {
              String[] fields = line.split(" ");
              if (fields.length == 2) {
                return new DexTestUtils.DexMetadata(Paths.get(fields[0]), fields[1]);
              } else {
                return new DexTestUtils.DexMetadata(Paths.get(fields[0]), fields[2]);
              }
            })
        .collect(ImmutableList.toImmutableList());
  }

  public static class DexMetadata implements Comparable<DexMetadata> {
    final Path dexFile;
    final String canaryName;

    public DexMetadata(Path dexFile, String canaryName) {
      this.dexFile = dexFile;
      this.canaryName = canaryName;
    }

    public String getJvmName() {
      return "L" + canaryName.replace('.', '/') + ";";
    }

    @Override
    public int compareTo(DexMetadata other) {
      return dexFile.compareTo(other.dexFile);
    }
  }
}

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

package com.facebook.buck.android.exopackage;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import java.nio.file.Path;
import java.util.Map;

public class ExopackageUtil {
  public static ImmutableMap<Path, Path> applyFilenameFormat(
      Map<String, Path> filesToHashes, Path deviceDir, String filenameFormat) {
    ImmutableMap.Builder<Path, Path> filesBuilder = ImmutableMap.builder();
    for (Map.Entry<String, Path> entry : filesToHashes.entrySet()) {
      filesBuilder.put(
          deviceDir.resolve(String.format(filenameFormat, entry.getKey())), entry.getValue());
    }
    return filesBuilder.build();
  }

  public static ImmutableMultimap<Path, Path> applyFilenameFormat(
      Multimap<String, Path> filesToHashes, Path deviceDir, String filenameFormat) {
    ImmutableMultimap.Builder<Path, Path> filesBuilder = ImmutableMultimap.builder();
    for (Map.Entry<String, Path> entry : filesToHashes.entries()) {
      filesBuilder.put(
          deviceDir.resolve(String.format(filenameFormat, entry.getKey())), entry.getValue());
    }
    return filesBuilder.build();
  }
}

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
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * If we end up creating both an obfuscator function and a deobfuscator function, it would be
 * nice to load the proguard mapping file once.  This class enables sharing that work.
 */
class ProguardTranslatorFactory {

  private final Optional<Map<String, String>> rawMap;

  private ProguardTranslatorFactory(Optional<Map<String, String>> rawMap) {
    this.rawMap = rawMap;
  }

  static ProguardTranslatorFactory create(
      ExecutionContext context,
      Optional<Path> proguardFullConfigFile,
      Optional<Path> proguardMappingFile)
      throws IOException {
    return new ProguardTranslatorFactory(
        loadOptionalRawMap(context, proguardFullConfigFile, proguardMappingFile));
  }

  @VisibleForTesting
  static ProguardTranslatorFactory createForTest(Optional<Map<String, String>> rawMap) {
    return new ProguardTranslatorFactory(rawMap);
  }

  private static Optional<Map<String, String>> loadOptionalRawMap(
      ExecutionContext context,
      Optional<Path> proguardFullConfigFile,
      Optional<Path> proguardMappingFile)
      throws IOException {
    if (!proguardFullConfigFile.isPresent()) {
      return Optional.absent();
    }

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    Path pathToProguardConfig = proguardFullConfigFile.get();

    // Proguard doesn't print a mapping when obfuscation is disabled.
    boolean obfuscationSkipped = Iterables.any(
        projectFilesystem.readLines(pathToProguardConfig),
        Predicates.equalTo("-dontobfuscate"));
    if (obfuscationSkipped) {
      return Optional.absent();
    }

    List<String> lines = projectFilesystem.readLines(proguardMappingFile.get());
    return Optional.of(ProguardMapping.readClassMapping(lines));
  }

  public Function<String, String> createDeobfuscationFunction() {
    return createFunction(false);
  }

  public Function<String, String> createObfuscationFunction() {
    return createFunction(true);
  }

  private Function<String, String> createFunction(boolean isForObfuscation) {
    if (!rawMap.isPresent()) {
      return Functions.identity();
    }

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : rawMap.get().entrySet()) {
      String original = entry.getKey().replace('.', '/');
      String obfuscated = entry.getValue().replace('.', '/');
      builder.put(
          isForObfuscation ? original : obfuscated,
          isForObfuscation ? obfuscated : original);
    }
    final Map<String, String> map = builder.build();

    return new Function<String, String>() {
      @Nullable
      @Override
      public String apply(@Nullable String input) {
        Preconditions.checkNotNull(input);
        String mapped = map.get(input);
        Preconditions.checkNotNull(mapped);
        return mapped;
      }
    };
  }
}

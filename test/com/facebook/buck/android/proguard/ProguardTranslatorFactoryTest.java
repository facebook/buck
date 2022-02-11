/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android.proguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProguardTranslatorFactoryTest {
  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  @Test
  public void testEnableObfuscation() throws IOException {
    Path proguardConfigFile = tempDir.newFile("configuration.txt").toPath();
    Path proguardMappingFile = tempDir.newFile("mapping.txt").toPath();

    List<String> linesInMappingFile =
        ImmutableList.of(
            "foo.bar.MappedPrimary -> foo.bar.a:",
            "foo.bar.UnmappedPrimary -> foo.bar.UnmappedPrimary:",
            "foo.primary.MappedPackage -> x.a:");

    Files.write(proguardConfigFile, ImmutableList.of());
    Files.write(proguardMappingFile, linesInMappingFile);

    ProguardTranslatorFactory translatorFactory =
        ProguardTranslatorFactory.create(
            Optional.of(proguardConfigFile), Optional.of(proguardMappingFile), false);
    checkMapping(translatorFactory, "foo/bar/MappedPrimary", "foo/bar/a");
    checkMapping(translatorFactory, "foo/bar/UnmappedPrimary", "foo/bar/UnmappedPrimary");
    checkMapping(translatorFactory, "foo/primary/MappedPackage", "x/a");

    assertNull(translatorFactory.createNullableObfuscationFunction().apply("foo/bar/NotInMapping"));
  }

  @Test
  public void testDisableObfuscation() throws IOException {
    Path proguardConfigFile = tempDir.newFile("configuration.txt").toPath();
    Path proguardMappingFile = tempDir.newFile("mapping.txt").toPath();
    Files.write(proguardConfigFile, ImmutableList.of("-dontobfuscate"));

    ProguardTranslatorFactory translatorFactory =
        ProguardTranslatorFactory.create(
            Optional.of(proguardConfigFile), Optional.of(proguardMappingFile), false);
    checkMapping(translatorFactory, "anything", "anything");
  }

  private void checkMapping(
      ProguardTranslatorFactory translatorFactory, String original, String obfuscated) {
    assertEquals(original, translatorFactory.createDeobfuscationFunction().apply(obfuscated));
    assertEquals(obfuscated, translatorFactory.createObfuscationFunction().apply(original));
    assertEquals(obfuscated, translatorFactory.createNullableObfuscationFunction().apply(original));
  }
}

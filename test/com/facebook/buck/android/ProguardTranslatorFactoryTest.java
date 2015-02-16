/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ProguardTranslatorFactoryTest {

  @Test
  public void testEnableObfuscation() throws IOException {
    Path proguardConfigFile = Paths.get("the/configuration.txt");
    Path proguardMappingFile = Paths.get("the/mapping.txt");
    List<String> linesInMappingFile = ImmutableList.of(
        "foo.bar.MappedPrimary -> foo.bar.a:",
        "foo.bar.UnmappedPrimary -> foo.bar.UnmappedPrimary:",
        "foo.primary.MappedPackage -> x.a:");

    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.readLines(proguardConfigFile))
        .andReturn(ImmutableList.<String>of());
    EasyMock.expect(projectFilesystem.readLines(proguardMappingFile))
        .andReturn(linesInMappingFile);

    ExecutionContext context = EasyMock.createMock(ExecutionContext.class);
    EasyMock.expect(context.getProjectFilesystem())
        .andReturn(projectFilesystem).anyTimes();

    EasyMock.replay(projectFilesystem, context);

    ProguardTranslatorFactory translatorFactory = ProguardTranslatorFactory.create(
        context, Optional.of(proguardConfigFile), Optional.of(proguardMappingFile));
    checkMapping(translatorFactory, "foo/bar/MappedPrimary", "foo/bar/a");
    checkMapping(translatorFactory, "foo/bar/UnmappedPrimary", "foo/bar/UnmappedPrimary");
    checkMapping(translatorFactory, "foo/primary/MappedPackage", "x/a");

    EasyMock.verify(projectFilesystem, context);
  }

  @Test
  public void testDisableObfuscation() throws IOException {
    Path proguardConfigFile = Paths.get("the/configuration.txt");
    Path proguardMappingFile = Paths.get("the/mapping.txt");

    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.readLines(proguardConfigFile))
        .andReturn(ImmutableList.of("-dontobfuscate"));

    ExecutionContext context = EasyMock.createMock(ExecutionContext.class);
    EasyMock.expect(context.getProjectFilesystem())
        .andReturn(projectFilesystem).anyTimes();

    EasyMock.replay(projectFilesystem, context);

    ProguardTranslatorFactory translatorFactory = ProguardTranslatorFactory.create(
        context, Optional.of(proguardConfigFile), Optional.of(proguardMappingFile));
    checkMapping(translatorFactory, "anything", "anything");

    EasyMock.verify(projectFilesystem, context);
  }

  private void checkMapping(
      ProguardTranslatorFactory translatorFactory,
      String original,
      String obfuscated) {
    assertEquals(original, translatorFactory.createDeobfuscationFunction().apply(obfuscated));
    assertEquals(obfuscated, translatorFactory.createObfuscationFunction().apply(original));
  }
}

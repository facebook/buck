/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProjectParserTest {
  @Test
  public void testExtractObjectsFromXcodeProject() throws IOException {
    String projectData =
      "// !$*UTF8*$!\n" +
      "{\n" +
      "  archiveVersion = 1;\n" +
      "  classes = {};\n" +
      "  objectVersion = 46;\n" +
      "  objects = {\n" +
      "    D97ABD8BAD38536B8B15AAC2 /* libFoo.a */ = {isa = PBXFileReference; explicitFileType = " +
      "      archive.ar; path = libFoo.a; sourceTree = BUILT_PRODUCTS_DIR; };\n" +
      "    E66DC04ECEB7F6CD00000000 /* Foo */ = {\n" +
      "      isa = PBXNativeTarget;\n" +
      "      buildConfigurationList = 7CC5FDCE622E7F7B4F76AB38 /* Build configuration list for " +
      "        PBXNativeTarget \"Foo\" */;\n" +
      "      buildPhases = (\n" +
      "      );\n" +
      "      buildRules = (\n" +
      "      );\n" +
      "      dependencies = (\n" +
      "      );\n" +
      "      name = Foo;\n" +
      "      productName = Foo;\n" +
      "      productReference = D97ABD8BAD38536B8B15AAC2 /* libFoo.a */;\n" +
      "      productType = \"com.apple.product-type.library.static\";\n" +
      "    };\n" +
      "  };\n" +
      "}";
    NSDictionary extractedObjects = ProjectParser.extractObjectsFromXcodeProject(
        new ByteArrayInputStream(projectData.getBytes(Charsets.UTF_8)));

    NSDictionary expectedObjects = new NSDictionary();
    NSDictionary libFooObject = new NSDictionary();
    libFooObject.put("isa", "PBXFileReference");
    libFooObject.put("explicitFileType", "archive.ar");
    libFooObject.put("path", "libFoo.a");
    libFooObject.put("sourceTree", "BUILT_PRODUCTS_DIR");
    expectedObjects.put("D97ABD8BAD38536B8B15AAC2", libFooObject);
    NSDictionary fooTarget = new NSDictionary();
    fooTarget.put("isa", "PBXNativeTarget");
    fooTarget.put("buildConfigurationList", "7CC5FDCE622E7F7B4F76AB38");
    fooTarget.put("buildPhases", new NSArray());
    fooTarget.put("buildRules", new NSArray());
    fooTarget.put("dependencies", new NSArray());
    fooTarget.put("name", "Foo");
    fooTarget.put("productName", "Foo");
    fooTarget.put("productReference", "D97ABD8BAD38536B8B15AAC2");
    fooTarget.put("productType", "com.apple.product-type.library.static");
    expectedObjects.put("E66DC04ECEB7F6CD00000000", fooTarget);

    assertEquals(expectedObjects, extractedObjects);
  }

  @Test
  public void testExtractTargetNameToGIDMap() {
    NSDictionary objects = new NSDictionary();
    NSDictionary nativeTarget = new NSDictionary();
    nativeTarget.put("isa", "PBXNativeTarget");
    nativeTarget.put("name", "Foo");
    objects.put("E66DC04ECEB7F6CD00000000", nativeTarget);

    ImmutableMap.Builder<String, String> fileReferenceMapBuilder =
      ImmutableMap.builder();
    ProjectParser.extractTargetNameToGIDMap(objects, fileReferenceMapBuilder);
    assertEquals(
        ImmutableMap.of("Foo", "E66DC04ECEB7F6CD00000000"),
        fileReferenceMapBuilder.build());
  }
}

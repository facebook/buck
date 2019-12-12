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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

public class AppModularityMetadataUtil {

  public static ImmutableMap<String, String> getClassToModuleMap(
      ProjectFilesystem filesystem, Path metadataFile) throws IOException {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

    List<String> metadataLines = filesystem.readLines(metadataFile);
    Iterator<String> lineIterator = metadataLines.iterator();

    // find classes header
    while (true) {
      if (!lineIterator.hasNext()) {
        // failed to hit class header
        return builder.build();
      }
      String headerCheck = lineIterator.next();
      if (headerCheck.equals(WriteAppModuleMetadataStep.CLASS_SECTION_HEADER)) {
        break;
      }
    }

    boolean inClassCheck = false;
    String currentModule = "";

    while (lineIterator.hasNext()) {
      String line = lineIterator.next();
      if (inClassCheck) {
        // check to see if the line is a class.
        if (line.startsWith(WriteAppModuleMetadataStep.ITEM_INDENTATION)) {
          String className = line.trim();
          // add the pair to the map.
          builder.put(className, currentModule);
        } else {
          // otherwise we are done with the current module.
          // and a module check is necessary.
          inClassCheck = false;
        }
      }
      if (!inClassCheck) {
        // if the module check fails we are done with class metadata
        if (!line.startsWith(WriteAppModuleMetadataStep.MODULE_INDENTATION)) {
          break;
        }
        // otherwise need to check classes for the new module.
        currentModule = line.trim();
        inClassCheck = true;
      }
    }
    return builder.build();
  }
}

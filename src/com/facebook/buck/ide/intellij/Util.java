/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij;

import com.facebook.buck.core.model.BuildTarget;

public abstract class Util {

  public static String intelliJModuleNameFromPath(String path) {
    String name = path;
    if (name.isEmpty()) {
      return "project_root";
    } else {
      return Util.normalizeIntelliJName(name);
    }
  }

  public static String intelliJLibraryName(BuildTarget target) {
    return target.getFullyQualifiedName();
  }

  public static String normalizeIntelliJName(String name) {
    return name.replace('.', '_')
        .replace('-', '_')
        .replace(':', '_')
        .replace(' ', '_')
        .replace('/', '_');
  }
}

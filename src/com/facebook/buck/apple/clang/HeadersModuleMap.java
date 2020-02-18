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

package com.facebook.buck.apple.clang;

import java.util.List;
import org.stringtemplate.v4.ST;

/**
 * Creates a modulemap file that uses an explicit `header` declaration for each header in the
 * module.
 */
public class HeadersModuleMap implements ModuleMap {
  private String moduleName;
  private List<String> headers;

  HeadersModuleMap(String moduleName, List<String> headers) {
    this.moduleName = moduleName;
    this.headers = headers;
  }

  private static final String template =
      "module <module_name> {\n"
          + "    <headers :{ name | header \"<name>\"\n }>"
          + "\n"
          + "    export *\n"
          + "}\n"
          + "\n";

  @Override
  public String render() {
    return new ST(template).add("module_name", moduleName).add("headers", headers).render();
  }
}

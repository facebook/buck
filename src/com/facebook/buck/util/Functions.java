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

package com.facebook.buck.util;

import com.google.common.base.Function;

import java.io.File;

public final class Functions {

  /** Utility class: do not instantiate. */
  private Functions() {}

  public static Function<File, String> FILE_TO_ABSOLUTE_PATH = new Function<File, String>() {
    @Override
    public String apply(File file) {
      return file.getAbsolutePath();
    }
  };

  public static Function<String, File> PATH_TO_FILE = new Function<String, File>() {
    @Override
    public File apply(String path) {
      return new File(path);
    }
  };

  public static Function<String, String> RELATIVE_TO_ABSOLUTE_PATH = new Function<String, String>() {
    @Override
    public String apply(String pathRelativeToProjectRoot) {
      return new File(pathRelativeToProjectRoot).getAbsolutePath();
    }
  };

}

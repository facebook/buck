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

package com.facebook.buck.java.classes;

import com.google.common.base.Preconditions;

/**
 * Utilities for common operations when working with {@link FileLike}s.
 */
public class FileLikes {

  /**
   * {@link FileLike} name suffix that identifies it as a Java class file.
   */
  private static final String CLASS_NAME_SUFFIX = ".class";

  /** Utility class: do not instantiate. */
  private FileLikes() {}

  public static boolean isClassFile(FileLike fileLike) {
    Preconditions.checkNotNull(fileLike);
    return fileLike.getRelativePath().endsWith(CLASS_NAME_SUFFIX);
  }

  public static String getFileNameWithoutClassSuffix(FileLike fileLike) {
    Preconditions.checkNotNull(fileLike);
    String name = fileLike.getRelativePath();
    return name.substring(0, name.length() - CLASS_NAME_SUFFIX.length());
  }
}

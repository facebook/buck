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

package com.facebook.buck.testutil;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;

public class WatchEvents {
  public static java.nio.file.WatchEvent<Path> createPathEvent(final File file, final java.nio.file.WatchEvent.Kind<Path> kind) {
    return new java.nio.file.WatchEvent<Path>() {
      @Override
      public Kind<Path> kind() {
        return kind;
      }

      @Override
      public int count() {
        return 0;
      }

      @Override
      public Path context() {
        return file.toPath();
      }
    };
  }

  public static java.nio.file.WatchEvent<Object> createOverflowEvent() {
    return new java.nio.file.WatchEvent<Object>() {
      @Override
      public Kind<Object> kind() {
        return StandardWatchEventKinds.OVERFLOW;
      }

      @Override
      public int count() {
        return 0;
      }

      @Override
      public Object context() {
        return null;
      }
    };
  }
}

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

package com.facebook.buck.testutil.integration;

import java.util.Map;
import java.util.Properties;

/**
 * A utility to temporarily set custom system properties and restore the originals afterward. This
 * is intended to be used as a the resource in a try-with-resource block.
 *
 * <p>This is useful for situations like integration tests in which certain resources should be
 * fetched from the buck repo rather than the temporary test workspace. If the locations of these
 * resources are specified by system properties (typically set during the `buck` invocation), then a
 * test may wish to spoof these properties so that the test invocation does not search for the
 * resources in the test workspace.
 */
public class PropertySaver implements AutoCloseable {
  private final Properties savedProperties;

  public PropertySaver(Map<String, String> newProperties) {
    savedProperties = System.getProperties();

    for (Map.Entry<String, String> entry : newProperties.entrySet()) {
      System.setProperty(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public void close() {
    System.setProperties(savedProperties);
  }
}

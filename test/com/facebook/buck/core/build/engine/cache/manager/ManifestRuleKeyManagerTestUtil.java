/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.build.engine.cache.manager;

import com.facebook.buck.core.rules.BuildRule;
import java.nio.file.Path;

/**
 * Test utility class that exposes a test visible method from {@link
 * com.facebook.buck.core.build.engine.cache.manager.ManifestRuleKeyManager} to tests belonging to
 * other packages.
 */
public class ManifestRuleKeyManagerTestUtil {

  public static Path getManifestPath(BuildRule rule) {
    return ManifestRuleKeyManager.getManifestPath(rule);
  }
}

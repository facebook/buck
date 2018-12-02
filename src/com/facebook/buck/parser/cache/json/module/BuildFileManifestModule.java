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

package com.facebook.buck.parser.cache.json.module;

import com.facebook.buck.parser.api.BuildFileManifest;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.module.SimpleModule;

/** Module that provides JSON serializers for {@link BuildFileManifest} class. */
public class BuildFileManifestModule extends SimpleModule {

  public static final Version VERSION =
      new Version(1, 0, 0, "", "com.facebook.buck.parser.api", "BuildFileManifestModule");

  public BuildFileManifestModule() {
    super("BuildFileManifestModule", VERSION);
    addDeserializer(BuildFileManifest.class, new BuildFileManifestObjectConverter());
  }
}

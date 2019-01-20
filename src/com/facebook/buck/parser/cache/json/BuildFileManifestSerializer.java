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

package com.facebook.buck.parser.cache.json;

import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;

/**
 * This class serializes the {@link BuildFileManifest} to a form that can be stored and read from
 * disk.
 */
public class BuildFileManifestSerializer {

  private BuildFileManifestSerializer() {}

  /**
   * Serializes an instance of {@link BuildFileManifest} to a byte array.
   *
   * @param buildFileManifest the instance of {@link BuildFileManifest} to be serialized.
   * @return a byte array with the serialized manifest.
   * @throws JsonProcessingException
   */
  public static byte[] serialize(BuildFileManifest buildFileManifest) throws IOException {
    return ObjectMappers.WRITER_WITH_TYPE.writeValueAsBytes(buildFileManifest);
  }

  /**
   * Deserializes an instance of {@link BuildFileManifest} from a byte array.
   *
   * @param buildFileManifestBytes the bytes for the manifest to be deserialized.
   * @return a new instance of {@link BuildFileManifest} serialized from the {@code
   *     buildFileManifestBytes}.
   * @throws IOException
   */
  public static BuildFileManifest deserialize(byte[] buildFileManifestBytes) throws IOException {
    return ObjectMappers.READER_WITH_TYPE
        .forType(BuildFileManifest.class)
        .readValue(buildFileManifestBytes);
  }
}

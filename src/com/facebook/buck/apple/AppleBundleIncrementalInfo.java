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

package com.facebook.buck.apple;

import com.facebook.buck.core.filesystems.RelPath;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Class to help serialize {@link RelPath} when it's present as a key in a map. */
class RelPathJsonKeySerializer extends JsonSerializer<RelPath> {

  @Override
  public void serialize(RelPath value, JsonGenerator gen, SerializerProvider serializers)
      throws IOException {
    gen.writeFieldName(value.toString());
  }
}

/** Class to help deserialize {@link RelPath} when it's present as a key in a map. */
class RelPathJsonKeyDeserializer extends KeyDeserializer {

  @Override
  public RelPath deserializeKey(String key, DeserializationContext context) {
    return RelPath.get(key);
  }
}

/** Class to help serialize {@link RelPath}. */
class RelPathJsonSerializer extends JsonSerializer<RelPath> {

  @Override
  public void serialize(RelPath value, JsonGenerator gen, SerializerProvider serializers)
      throws IOException {
    gen.writeString(value.toString());
  }
}

/** Class to help deserialize {@link RelPath}. */
class RelPathJsonDeserializer extends JsonDeserializer<RelPath> {

  @Override
  public RelPath deserialize(JsonParser p, DeserializationContext context) throws IOException {
    return RelPath.get(p.getValueAsString());
  }
}

/**
 * Container for all the information to be serialized and used in the next build if it will
 * incremental.
 */
public class AppleBundleIncrementalInfo {
  @JsonProperty("hashes")
  @JsonSerialize(keyUsing = RelPathJsonKeySerializer.class)
  @JsonDeserialize(keyUsing = RelPathJsonKeyDeserializer.class)
  private Map<RelPath, String> hashes;

  @JsonProperty("codeSigned")
  private boolean codeSigned;

  @JsonProperty("codeSignedOnCopyPaths")
  @JsonSerialize(contentUsing = RelPathJsonSerializer.class)
  @JsonDeserialize(contentUsing = RelPathJsonDeserializer.class)
  private List<RelPath> codeSignedOnCopyPaths;

  public AppleBundleIncrementalInfo() {}

  public AppleBundleIncrementalInfo(
      Map<RelPath, String> hashes, boolean codeSigned, List<RelPath> codeSignedOnCopyPaths) {
    this.hashes = hashes;
    this.codeSigned = codeSigned;
    this.codeSignedOnCopyPaths = codeSignedOnCopyPaths;
  }

  public Map<RelPath, String> getHashes() {
    return hashes;
  }

  public boolean codeSigned() {
    return codeSigned;
  }

  public List<RelPath> getCodeSignedOnCopyPaths() {
    return codeSignedOnCopyPaths;
  }
}

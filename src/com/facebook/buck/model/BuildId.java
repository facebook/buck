/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * A strongly typed representation of a build id.
 */
@JsonSerialize(using = BuildId.BuildIdSerializer.class)
public class BuildId implements Comparable<BuildId> {

  private static final Random INSECURE_RANDOM = new Random();

  private final String id;

  public BuildId() {
    this(new UUID(INSECURE_RANDOM.nextLong(), INSECURE_RANDOM.nextLong()).toString());
  }

  public BuildId(String id) {
    this.id = Preconditions.checkNotNull(id);
  }

  @JsonCreator
  public static BuildId fromJson(String id) {
    return new BuildId(id);
  }

  @Override
  public String toString() {
    return id;
  }

  @Override
  public int compareTo(BuildId other) {
    if (other == null) {
      return 1;
    }
    return id.compareTo(other.id);
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof BuildId)) {
      return false;
    }

    return Objects.equals(this.id, ((BuildId) other).id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  public static class BuildIdSerializer extends JsonSerializer<BuildId> {

    @Override
    public void serialize(
        BuildId buildId,
        JsonGenerator jsonGenerator,
        SerializerProvider serializerProvider) throws IOException {
      jsonGenerator.writeString(buildId.id);
    }
  }
}

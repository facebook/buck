/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.rules.modern;

/**
 * A {@link CustomFieldSerialization} that will disable remote execution for the referencing rule if
 * the field holds a false value.
 */
public class RemoteExecutionEnabled implements CustomFieldSerialization<Boolean> {
  @Override
  public <E extends Exception> void serialize(Boolean value, ValueVisitor<E> serializer) throws E {
    if (!value) {
      // RemoteExecution is currently disabled for anything that fails to serialize. Throwing an
      // exception in serialization is a simple way to disable it.
      throw new DisableRemoteExecutionException();
    }
  }

  @Override
  public <E extends Exception> Boolean deserialize(ValueCreator<E> deserializer) throws E {
    return true;
  }

  private class DisableRemoteExecutionException extends RuntimeException {}
}

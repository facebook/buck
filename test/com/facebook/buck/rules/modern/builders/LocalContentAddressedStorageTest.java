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

package com.facebook.buck.rules.modern.builders;

import static org.junit.Assert.*;

import com.facebook.buck.rules.modern.builders.InputsDigestBuilder.DefaultDelegate;
import com.facebook.buck.rules.modern.builders.thrift.Digest;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class LocalContentAddressedStorageTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private LocalContentAddressedStorage storage;
  private Path storageDir;

  @Before
  public void setUp() {
    storageDir = tmp.getRoot().resolve("__storage__");
    storage =
        new LocalContentAddressedStorage(storageDir, InputsDigestBuilder::defaultDigestForStruct);
  }

  @Test
  public void canAddData() throws IOException {
    byte[] data = "hello world!".getBytes(Charsets.UTF_8);
    Digest digest = new Digest("myhashcode", data.length);
    storage.addMissing(ImmutableMap.of(digest, () -> new ByteArrayInputStream(data)));
    assertDataEquals(data, storage.getData(digest));
  }

  private void assertDataEquals(byte[] expected, byte[] actual) {
    assertEquals(new String(expected, Charsets.UTF_8), new String(actual, Charsets.UTF_8));
  }

  @Test
  public void presentDataIsNotAdded() throws IOException {
    byte[] data = "hello world!".getBytes(Charsets.UTF_8);
    Digest digest = new Digest("myhashcode", data.length);
    storage.addMissing(ImmutableMap.of(digest, () -> new ByteArrayInputStream(data)));
    storage.addMissing(
        ImmutableMap.of(
            digest,
            () -> {
              throw new RuntimeException();
            }));
  }

  @Test
  public void addingAndMaterializingFullInputsWorks() throws IOException {
    InputsDigestBuilder inputsBuilder =
        new InputsDigestBuilder(
            new DefaultDelegate(
                tmp.getRoot(),
                path -> {
                  throw new RuntimeException();
                }));
    Path somePath = Paths.get("dir/some.path");
    byte[] someData = "hello world!".getBytes(Charsets.UTF_8);
    inputsBuilder.addFile(somePath, () -> someData, false);
    Path otherPath = Paths.get("dir/other.path");
    byte[] otherData = "goodbye world!".getBytes(Charsets.UTF_8);
    inputsBuilder.addFile(otherPath, () -> otherData, false);
    Inputs inputs = inputsBuilder.build();
    storage.addMissing(inputs.getRequiredData());

    Path inputsDir = tmp.getRoot().resolve("inputs");
    storage.materializeInputs(inputsDir, inputs.getRootDigest());

    assertDataEquals(someData, Files.readAllBytes(inputsDir.resolve(somePath)));
    assertDataEquals(otherData, Files.readAllBytes(inputsDir.resolve(otherPath)));
  }
}

/*
 * Copyright 2015-present Facebook, Inc.
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
package com.facebook.buck.rules.coercer;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

/** Test coercion of map types to {@link ManifestEntries} */
public class ManifestEntriesTypeCoercerTest {

  private ManifestEntriesTypeCoercer manifestEntriesTypeCoercer;

  private ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private Path basePath = Paths.get("java/com/facebook/buck/example");

  @Before
  public void setUp() {
    DefaultTypeCoercerFactory factory = new DefaultTypeCoercerFactory();
    TypeCoercer<?> typeCoercer = factory.typeCoercerForType(ManifestEntries.class);
    assertTrue(typeCoercer instanceof ManifestEntriesTypeCoercer);
    manifestEntriesTypeCoercer = (ManifestEntriesTypeCoercer) typeCoercer;
  }

  @Test
  public void shouldAcceptWellFormedInput() throws Exception {
    ImmutableMap<String, Object> inputMap =
        ImmutableMap.<String, Object>builder()
            .put("min_sdk_version", 3)
            .put("target_sdk_version", 5)
            .put("debug_mode", true)
            .put("version_code", 7)
            .put("version_name", "eleven")
            .build();

    ManifestEntries result =
        manifestEntriesTypeCoercer.coerce(
            createCellRoots(filesystem),
            filesystem,
            basePath,
            EmptyTargetConfiguration.INSTANCE,
            inputMap);

    assertTrue(result.getDebugMode().get());
    assertEquals(3, result.getMinSdkVersion().getAsInt());
    assertEquals(5, result.getTargetSdkVersion().getAsInt());
    assertEquals(7, result.getVersionCode().getAsInt());
    assertEquals("eleven", result.getVersionName().get());
  }

  @Test(expected = CoerceFailedException.class)
  public void shouldThrowCoerceFailedExceptionOnUnrecognizedParam() throws Exception {
    ImmutableMap<String, Object> inputMap =
        ImmutableMap.<String, Object>builder().put("bad_param_name", 3).build();

    manifestEntriesTypeCoercer.coerce(
        createCellRoots(filesystem),
        filesystem,
        basePath,
        EmptyTargetConfiguration.INSTANCE,
        inputMap);
  }

  @Test
  public void shouldUseAbsentForMissingItems() throws Exception {
    ImmutableMap<String, Object> inputMap =
        ImmutableMap.<String, Object>builder()
            .put("min_sdk_version", 3)
            .put("target_sdk_version", 5)
            .put("debug_mode", true)
            .build();

    ManifestEntries result =
        manifestEntriesTypeCoercer.coerce(
            createCellRoots(filesystem),
            filesystem,
            basePath,
            EmptyTargetConfiguration.INSTANCE,
            inputMap);

    assertTrue(result.getDebugMode().get());
    assertEquals(3, result.getMinSdkVersion().getAsInt());
    assertEquals(5, result.getTargetSdkVersion().getAsInt());
    assertFalse(result.getVersionCode().isPresent());
    assertFalse(result.getVersionName().isPresent());
  }
}

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

package com.facebook.buck.remoteexecution.config;

import static com.facebook.buck.remoteexecution.config.RemoteExecutionConfig.AUTO_RE_EXPERIMENT_PROPERTY_KEY;
import static com.facebook.buck.remoteexecution.config.RemoteExecutionConfig.DEFAULT_AUTO_RE_EXPERIMENT_PROPERTY;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.remoteexecution.proto.RESessionID;
import com.google.common.collect.ImmutableMap;
import org.junit.Assert;
import org.junit.Test;

public class RemoteExecutionConfigTest {

  private static final RESessionID RE_SESSION_ID =
      RESessionID.newBuilder().setId("topspin").build();

  @Test
  public void testGetDebugURLStringWithoutFormatString() {
    RemoteExecutionConfig config = getConfig("");
    Assert.assertEquals("topspin", config.getDebugURLString(RE_SESSION_ID));
  }

  @Test
  public void testGetDebugURLStringWithFormatStringNotContainingMarker() {
    RemoteExecutionConfig config = getConfig("https://localhost/test?blah=");
    Assert.assertEquals("https://localhost/test?blah=", config.getDebugURLString(RE_SESSION_ID));
  }

  @Test
  public void testGetDebugURLStringWithFormatString() {
    RemoteExecutionConfig config = getConfig("https://localhost/test?blah={id}");
    Assert.assertEquals(
        "https://localhost/test?blah=topspin", config.getDebugURLString(RE_SESSION_ID));
  }

  @Test
  public void testValidationOfCertificateFiles() {
    BuckConfig config =
        FakeBuckConfig.builder().setSections("[remoteexecution]", "ca=does_not_exist").build();
    RemoteExecutionConfig reConfig = config.getView(RemoteExecutionConfig.class);
    try {
      reConfig.validateCertificatesOrThrow();
      Assert.fail("Should've thrown.");
    } catch (HumanReadableException e) {
      Assert.assertEquals(
          "Config [remoteexecution.cert] must point to a file, value [] or [remoteexecution.cert_env_var] must be set to a valid file location, value [].\n"
              + "Config [remoteexecution.key] must point to a file, value [] or [remoteexecution.key_env_var] must be set to a valid file location, value [].\n"
              + "Config [remoteexecution.ca] points to file [does_not_exist] that does not exist.",
          e.getMessage());
    }
  }

  @Test
  public void testIsExperimentEnabled() {
    BuckConfig configWithCustomExperimentEnabled =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "remoteexecution",
                        ImmutableMap.of(
                            AUTO_RE_EXPERIMENT_PROPERTY_KEY, "some_experiment_property"),
                    "experiments", ImmutableMap.of("some_experiment_property", "true")))
            .build();
    RemoteExecutionConfig reConfigWithCustomExperimentEnabled =
        configWithCustomExperimentEnabled.getView(RemoteExecutionConfig.class);
    Assert.assertTrue(reConfigWithCustomExperimentEnabled.isExperimentEnabled());

    BuckConfig configWithCustomExperimentDisabled =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "remoteexecution",
                        ImmutableMap.of(
                            AUTO_RE_EXPERIMENT_PROPERTY_KEY, "some_experiment_property"),
                    "experiments", ImmutableMap.of("some_experiment_property", "false")))
            .build();
    RemoteExecutionConfig reConfigWithCustomExperimentDisabled =
        configWithCustomExperimentDisabled.getView(RemoteExecutionConfig.class);
    Assert.assertFalse(reConfigWithCustomExperimentDisabled.isExperimentEnabled());

    BuckConfig configWithCustomExperimentNotSet =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "remoteexecution",
                    ImmutableMap.of(AUTO_RE_EXPERIMENT_PROPERTY_KEY, "some_experiment_property")))
            .build();
    RemoteExecutionConfig reConfigWithCustomExperimentNotSet =
        configWithCustomExperimentNotSet.getView(RemoteExecutionConfig.class);
    Assert.assertFalse(reConfigWithCustomExperimentNotSet.isExperimentEnabled());

    BuckConfig configWithDefaultExperimentEnabled =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "experiments", ImmutableMap.of(DEFAULT_AUTO_RE_EXPERIMENT_PROPERTY, "true")))
            .build();
    RemoteExecutionConfig reConfigWithDefaultExperimentEnabled =
        configWithDefaultExperimentEnabled.getView(RemoteExecutionConfig.class);
    Assert.assertTrue(reConfigWithDefaultExperimentEnabled.isExperimentEnabled());

    BuckConfig configWithDefaultExperimentDisabled =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "experiments", ImmutableMap.of(DEFAULT_AUTO_RE_EXPERIMENT_PROPERTY, "false")))
            .build();
    RemoteExecutionConfig reConfigWithDefaultExperimentDisabled =
        configWithDefaultExperimentDisabled.getView(RemoteExecutionConfig.class);
    Assert.assertFalse(reConfigWithDefaultExperimentDisabled.isExperimentEnabled());
  }

  @Test
  public void testIsRemoteExecutionAutoEnabled() {
    Assert.assertFalse(
        RemoteExecutionConfig.isRemoteExecutionAutoEnabled(
            true, true, AutoRemoteExecutionStrategy.DISABLED));

    Assert.assertTrue(
        RemoteExecutionConfig.isRemoteExecutionAutoEnabled(
            false, true, AutoRemoteExecutionStrategy.RE_IF_EXPERIMENT_ENABLED));

    Assert.assertFalse(
        RemoteExecutionConfig.isRemoteExecutionAutoEnabled(
            true, false, AutoRemoteExecutionStrategy.RE_IF_EXPERIMENT_ENABLED));

    Assert.assertFalse(
        RemoteExecutionConfig.isRemoteExecutionAutoEnabled(
            false, true, AutoRemoteExecutionStrategy.RE_IF_WHITELIST_MATCH));

    Assert.assertTrue(
        RemoteExecutionConfig.isRemoteExecutionAutoEnabled(
            true, false, AutoRemoteExecutionStrategy.RE_IF_WHITELIST_MATCH));

    Assert.assertFalse(
        RemoteExecutionConfig.isRemoteExecutionAutoEnabled(
            true, false, AutoRemoteExecutionStrategy.RE_IF_EXPERIMENT_ENABLED_AND_WHITELIST_MATCH));

    Assert.assertFalse(
        RemoteExecutionConfig.isRemoteExecutionAutoEnabled(
            false, true, AutoRemoteExecutionStrategy.RE_IF_EXPERIMENT_ENABLED_AND_WHITELIST_MATCH));

    Assert.assertTrue(
        RemoteExecutionConfig.isRemoteExecutionAutoEnabled(
            true, true, AutoRemoteExecutionStrategy.RE_IF_EXPERIMENT_ENABLED_AND_WHITELIST_MATCH));

    Assert.assertTrue(
        RemoteExecutionConfig.isRemoteExecutionAutoEnabled(
            true, false, AutoRemoteExecutionStrategy.RE_IF_EXPERIMENT_ENABLED_OR_WHITELIST_MATCH));
    Assert.assertTrue(
        RemoteExecutionConfig.isRemoteExecutionAutoEnabled(
            false, true, AutoRemoteExecutionStrategy.RE_IF_EXPERIMENT_ENABLED_OR_WHITELIST_MATCH));
  }

  private RemoteExecutionConfig getConfig(String debugFormatString) {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections("[remoteexecution]", "debug_format_string_url=" + debugFormatString)
            .build();
    return config.getView(RemoteExecutionConfig.class);
  }
}

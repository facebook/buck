/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.android.redex;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.KeystoreProperties;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.Test;

public class ReDexStepTest {
  @Test
  public void constructorArgsAreUsedToCreateShellCommand() {
    Path workingDirectory = Paths.get("/where/the/code/is");
    List<String> redexBinaryArgs = ImmutableList.of("/usr/bin/redex");
    Map<String, String> redexEnvironmentVariables = ImmutableMap.of("REDEX_DEBUG", "1");
    Path inputApkPath = Paths.get("buck-out/gen/app.apk.zipalign");
    Path outputApkPath = Paths.get("buck-out/gen/app.apk");
    Path keystorePath = Paths.get("keystores/debug.keystore");
    KeystoreProperties keystoreProperties =
        new KeystoreProperties(keystorePath, "storepass", "keypass", "alias");
    Supplier<KeystoreProperties> keystorePropertiesSupplier =
        Suppliers.ofInstance(keystoreProperties);
    Path redexConfigPath = Paths.get("redex/redex-config.json");
    Optional<Path> redexConfig = Optional.of(redexConfigPath);
    ImmutableList<Arg> redexExtraArgs = ImmutableList.of(StringArg.of("foo"), StringArg.of("bar"));
    Path proguardMap = Paths.get("buck-out/gen/app/__proguard__/mapping.txt");
    Path proguardConfig = Paths.get("app.proguard.config");
    Path seeds = Paths.get("buck-out/gen/app/__proguard__/seeds.txt");

    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));

    Path sdkDirectory = Paths.get("/Users/user/android-sdk-macosx");

    ReDexStep redex =
        new ReDexStep(
            BuildTargetFactory.newInstance("//dummy:target"),
            workingDirectory,
            AndroidSdkLocation.of(sdkDirectory),
            redexBinaryArgs,
            redexEnvironmentVariables,
            inputApkPath,
            outputApkPath,
            keystorePropertiesSupplier,
            redexConfig,
            redexExtraArgs,
            proguardMap,
            proguardConfig,
            seeds,
            pathResolver);

    assertEquals("redex", redex.getShortName());

    ExecutionContext context = TestExecutionContext.newBuilder().build();
    assertEquals(
        ImmutableMap.of("ANDROID_SDK", sdkDirectory.toString(), "REDEX_DEBUG", "1"),
        redex.getEnvironmentVariables(context));

    assertEquals(
        ImmutableList.of(
            "/usr/bin/redex",
            "--config",
            redexConfigPath.toString(),
            "--sign",
            "--keystore",
            keystorePath.toString(),
            "--keyalias",
            "alias",
            "--keypass",
            "keypass",
            "--proguard-map",
            proguardMap.toString(),
            "-P",
            proguardConfig.toString(),
            "--keep",
            seeds.toString(),
            "--out",
            outputApkPath.toString(),
            "foo",
            "bar",
            inputApkPath.toString()),
        redex.getShellCommandInternal(context));
  }
}

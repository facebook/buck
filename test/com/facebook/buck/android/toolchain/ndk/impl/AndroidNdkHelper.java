/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android.toolchain.ndk.impl;

import static org.junit.Assert.assertFalse;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.relinker.Symbols;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformCompiler;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntimeType;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.zip.ZipFile;
import org.tukaani.xz.XZInputStream;

public class AndroidNdkHelper {

  private static final Logger LOG = Logger.get(AndroidNdkHelper.class);

  private AndroidNdkHelper() {}

  public static final AndroidBuckConfig DEFAULT_CONFIG =
      new AndroidBuckConfig(FakeBuckConfig.builder().build(), Platform.detect());

  public static Optional<AndroidNdk> detectAndroidNdk(ProjectFilesystem filesystem) {
    Optional<AndroidNdk> androidNdk;
    try {
      androidNdk =
          new AndroidNdkFactory()
              .createToolchain(
                  new ToolchainProviderBuilder().build(),
                  ToolchainCreationContext.of(
                      ImmutableMap.copyOf(System.getenv()),
                      FakeBuckConfig.builder().build(),
                      filesystem,
                      new DefaultProcessExecutor(new TestConsole()),
                      new ExecutableFinder(),
                      TestRuleKeyConfigurationFactory.create()));
    } catch (HumanReadableException e) {
      LOG.warn(e, "Cannot detect Android NDK");
      androidNdk = Optional.empty();
    }
    return androidNdk;
  }

  public static NdkCxxPlatform getNdkCxxPlatform(ProjectFilesystem filesystem) {
    // TODO(cjhopman): is this really the simplest way to get the objdump tool?
    Optional<AndroidNdk> androidNdk = detectAndroidNdk(filesystem);

    Path ndkPath = androidNdk.get().getNdkRootPath();
    String ndkVersion = AndroidNdkResolver.findNdkVersionFromDirectory(ndkPath).get();
    String gccVersion = NdkCxxPlatforms.getDefaultGccVersionForNdk(ndkVersion);

    ImmutableCollection<NdkCxxPlatform> platforms =
        NdkCxxPlatforms.getPlatforms(
                CxxPlatformUtils.DEFAULT_CONFIG,
                AndroidNdkHelper.DEFAULT_CONFIG,
                filesystem,
                ndkPath,
                NdkCxxPlatformCompiler.builder()
                    .setType(NdkCxxPlatforms.DEFAULT_COMPILER_TYPE)
                    .setVersion(gccVersion)
                    .setGccVersion(gccVersion)
                    .build(),
                NdkCxxPlatforms.DEFAULT_CXX_RUNTIME,
                NdkCxxRuntimeType.DYNAMIC,
                getDefaultCpuAbis(ndkVersion),
                Platform.detect())
            .values();
    assertFalse(platforms.isEmpty());
    return platforms.iterator().next();
  }

  private static Path unzip(Path tmpDir, Path zipPath, String name) throws IOException {
    File nameFile = new File(name);
    Path outPath = tmpDir.resolve(zipPath.getFileName() + "_" + nameFile.getName());
    try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
      Files.copy(
          zipFile.getInputStream(zipFile.getEntry(name)),
          outPath,
          StandardCopyOption.REPLACE_EXISTING);
      return outPath;
    }
  }

  public static class SymbolGetter {
    private final ProcessExecutor executor;
    private final Path tmpDir;
    private final Tool objdump;
    private final SourcePathResolver resolver;

    public SymbolGetter(
        ProcessExecutor executor, Path tmpDir, Tool objdump, SourcePathResolver resolver) {
      this.executor = executor;
      this.tmpDir = tmpDir;
      this.objdump = objdump;
      this.resolver = resolver;
    }

    private Path unpack(Path apkPath, String libName) throws IOException {
      new ZipInspector(apkPath).assertFileExists(libName);
      return unzip(tmpDir, apkPath, libName);
    }

    private void advanceStream(InputStream stream, int bytes) throws IOException {
      byte[] buf = new byte[bytes];
      int read = stream.read(buf, 0, bytes);
      if (read != bytes) {
        throw new IOException("unable to read " + bytes + " bytes");
      }
    }

    public Symbols getDynamicSymbols(Path apkPath, String libName)
        throws IOException, InterruptedException {
      Path lib = unpack(apkPath, libName);
      return Symbols.getDynamicSymbols(executor, objdump, resolver, lib);
    }

    public Symbols getNormalSymbols(Path apkPath, String libName)
        throws IOException, InterruptedException {
      Path lib = unpack(apkPath, libName);
      return Symbols.getNormalSymbols(executor, objdump, resolver, lib);
    }

    public Symbols getDynamicSymbolsFromFile(Path sharedObject)
        throws IOException, InterruptedException {
      return Symbols.getDynamicSymbols(executor, objdump, resolver, sharedObject);
    }

    public Symbols getNormalSymbolsFromFile(Path sharedObject)
        throws IOException, InterruptedException {
      return Symbols.getNormalSymbols(executor, objdump, resolver, sharedObject);
    }

    public Symbols getXzsSymbols(Path apkPath, String libName, String xzsName, String metadataName)
        throws IOException, InterruptedException {
      Path xzs = unpack(apkPath, xzsName);
      Path metadata = unpack(apkPath, metadataName);
      Path lib = tmpDir.resolve(libName);
      try (BufferedReader metadataReader = new BufferedReader(new FileReader(metadata.toFile()))) {
        try (XZInputStream xzInput =
            new XZInputStream(new FileInputStream(xzs.toFile()), -1, false)) {
          String line = metadataReader.readLine();
          while (line != null) {
            String[] tokens = line.split(" ");
            File metadataFile = new File(tokens[0]);
            if (metadataFile.getName().equals(libName)) {
              break;
            }
            advanceStream(xzInput, Integer.parseInt(tokens[1]));
            line = metadataReader.readLine();
          }
          Files.copy(xzInput, lib, StandardCopyOption.REPLACE_EXISTING);
        }
      }
      return Symbols.getDynamicSymbols(executor, objdump, resolver, lib);
    }

    public SymbolsAndDtNeeded getSymbolsAndDtNeeded(Path apkPath, String libName)
        throws IOException, InterruptedException {
      Path lib = unpack(apkPath, libName);
      Symbols symbols = Symbols.getDynamicSymbols(executor, objdump, resolver, lib);
      ImmutableSet<String> dtNeeded = Symbols.getDtNeeded(executor, objdump, resolver, lib);
      return new SymbolsAndDtNeeded(symbols, dtNeeded);
    }
  }

  public static class SymbolsAndDtNeeded {
    public final Symbols symbols;
    public final ImmutableSet<String> dtNeeded;

    private SymbolsAndDtNeeded(Symbols symbols, ImmutableSet<String> dtNeeded) {
      this.symbols = symbols;
      this.dtNeeded = dtNeeded;
    }
  }

  public static ImmutableSet<String> getDefaultCpuAbis(String ndkVersion) {
    return NdkCxxPlatforms.getDefaultCpuAbis(ndkVersion);
  }
}

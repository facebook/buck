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

package com.facebook.buck.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.relinker.Symbols;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.zip.ZipFile;

public class AndroidNdkHelper {

  private AndroidNdkHelper() {}

  public static final AndroidBuckConfig DEFAULT_CONFIG =
      new AndroidBuckConfig(FakeBuckConfig.builder().build(), Platform.detect());

  public static NdkCxxPlatform getNdkCxxPlatform(
      ProjectWorkspace workspace, ProjectFilesystem filesystem)
      throws IOException, InterruptedException {
    // TODO(cjhopman): is this really the simplest way to get the objdump tool?
    AndroidDirectoryResolver androidResolver =
        new DefaultAndroidDirectoryResolver(
            workspace.asCell().getRoot().getFileSystem(),
            ImmutableMap.copyOf(System.getenv()),
            Optional.empty(),
            Optional.empty());

    Optional<Path> ndkPath = androidResolver.getNdkOrAbsent();
    assertTrue(ndkPath.isPresent());
    Optional<String> ndkVersion =
        DefaultAndroidDirectoryResolver.findNdkVersionFromDirectory(ndkPath.get());
    String gccVersion = NdkCxxPlatforms.getDefaultGccVersionForNdk(ndkVersion);

    ImmutableCollection<NdkCxxPlatform> platforms =
        NdkCxxPlatforms.getPlatforms(
                CxxPlatformUtils.DEFAULT_CONFIG,
                AndroidNdkHelper.DEFAULT_CONFIG,
                filesystem,
                ndkPath.get(),
                NdkCxxPlatformCompiler.builder()
                    .setType(NdkCxxPlatforms.DEFAULT_COMPILER_TYPE)
                    .setVersion(gccVersion)
                    .setGccVersion(gccVersion)
                    .build(),
                NdkCxxPlatforms.DEFAULT_CXX_RUNTIME,
                NdkCxxPlatforms.DEFAULT_TARGET_APP_PLATFORM,
                NdkCxxPlatforms.DEFAULT_CPU_ABIS,
                Platform.detect())
            .values();
    assertFalse(platforms.isEmpty());
    return platforms.iterator().next();
  }

  private static Path unzip(Path tmpDir, Path zipPath, String name) throws IOException {
    Path outPath = tmpDir.resolve(zipPath.getFileName());
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

    public Symbols getSymbols(Path apkPath, String libName)
        throws IOException, InterruptedException {
      Path lib = unpack(apkPath, libName);
      return Symbols.getSymbols(executor, objdump, resolver, lib);
    }

    public SymbolsAndDtNeeded getSymbolsAndDtNeeded(Path apkPath, String libName)
        throws IOException, InterruptedException {
      Path lib = unpack(apkPath, libName);
      Symbols symbols = Symbols.getSymbols(executor, objdump, resolver, lib);
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
}

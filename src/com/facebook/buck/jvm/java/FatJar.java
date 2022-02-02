/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

/**
 * *************
 *
 * <p>This code can be embedded in arbitrary third-party projects! For maximum compatibility, use
 * only Java 7 constructs.
 *
 * <p>*************
 */
package com.facebook.buck.jvm.java;

import com.facebook.buck.util.liteinfersupport.Nullable;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamConstants;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Helper class for unpacking fat JAR resources. */
public class FatJar implements Serializable {

  /**
   * Used by the serialization runtime for versioning. Increment this if you add/remove fields or
   * change their semantics.
   */
  private static final long serialVersionUID = 1L;

  public static final String FAT_JAR_INNER_JAR = "inner.jar";
  public static final String FAT_JAR_INFO_RESOURCE = "fat_jar_info.dat";
  public static final String FAT_JAR_NATIVE_LIBRARIES_DIR = "nativelibs";

  @Nullable private final Boolean wrapperScript;

  public FatJar(boolean wrapperScript) {
    this.wrapperScript = wrapperScript;
  }

  /** @return the {@link FatJar} object deserialized from the resource name via {@code loader}. */
  public static FatJar load(ClassLoader loader) throws ClassNotFoundException, IOException {
    try (InputStream inputStream = loader.getResourceAsStream(FAT_JAR_INFO_RESOURCE);
        BufferedInputStream bufferedInputStream =
            new BufferedInputStream(Objects.requireNonNull(inputStream));
        ObjectInputStream objectInputStream = new ObjectInputStream(bufferedInputStream)) {
      return (FatJar) objectInputStream.readObject();
    }
  }

  /** Serialize this instance as binary to {@code outputStream}. */
  public void store(OutputStream outputStream) throws IOException {
    try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream)) {
      // Explicitly specify a protocol version, just in case the default protocol gets updated with
      // a new version of Java. We need to ensure the serialized data can be read by older versions
      // of Java, as the fat jar stub, which references this class, is compiled against an older
      // version of Java for compatibility purposes, unlike the main Buck jar, which also references
      // this class.
      objectOutputStream.useProtocolVersion(ObjectStreamConstants.PROTOCOL_VERSION_2);
      objectOutputStream.writeObject(this);
    }
  }

  static void unpackNativeLibrariesInto(Path destination) throws IOException {
    try (JarFile jar = new JarFile(getJarPath())) {
      Enumeration<JarEntry> enumEntries = jar.entries();
      while (enumEntries.hasMoreElements()) {
        JarEntry jarEntry = enumEntries.nextElement();
        String entryName = jarEntry.getName();
        if (jarEntry.isDirectory() || !entryName.startsWith(FAT_JAR_NATIVE_LIBRARIES_DIR)) {
          continue;
        }

        String fileName = entryName.substring(FAT_JAR_NATIVE_LIBRARIES_DIR.length() + 1);
        Files.copy(jar.getInputStream(jarEntry), destination.resolve(fileName));
      }
    }
  }

  private static String getJarPath() throws IOException {
    ProtectionDomain protectionDomain = FatJar.class.getProtectionDomain();
    CodeSource codeSource = protectionDomain.getCodeSource();
    URL jarUrl = new URL("jar:" + codeSource.getLocation().toExternalForm() + "!/");
    URL jarFileUrl = ((JarURLConnection) jarUrl.openConnection()).getJarFileURL();
    return jarFileUrl.getPath();
  }

  static void unpackInnerArtifactTo(ClassLoader loader, Path destination) throws IOException {
    try (InputStream input = loader.getResourceAsStream(FAT_JAR_INNER_JAR);
        BufferedInputStream bufferedInput =
            new BufferedInputStream(Objects.requireNonNull(input))) {
      Files.copy(bufferedInput, destination);
    }
  }

  boolean isWrapperScript() {
    return wrapperScript;
  }
}

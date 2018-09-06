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

/**
 * *************
 *
 * <p>This code can be embedded in arbitrary third-party projects! For maximum compatibility, use
 * only Java 6 constructs.
 *
 * <p>*************
 */
package com.facebook.buck.jvm.java;

import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamConstants;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Helper class for unpacking fat JAR resources. */
public class FatJar implements Serializable {

  /**
   * Used by the serialization runtime for versioning. Increment this if you add/remove fields or
   * change their semantics.
   */
  private static final long serialVersionUID = 1L;

  public static final String FAT_JAR_INFO_RESOURCE = "fat_jar_info.dat";

  /** The resource name for the real JAR. */
  @Nullable private String innerJar;

  /**
   * The map of system-specific shared library names to their corresponding resource names. Note: We
   * purposely use <code>HashMap</code> instead of <code>Map</code> here to ensure serializability
   * of this class.
   */
  @SuppressWarnings("PMD.LooseCoupling")
  @Nullable
  private HashMap<String, String> nativeLibraries;

  public FatJar(String innerJar, Map<String, String> nativeLibraries) {
    this.innerJar = innerJar;
    this.nativeLibraries = new HashMap<String, String>(nativeLibraries);
  }

  /** @return the {@link FatJar} object deserialized from the resource name via {@code loader}. */
  public static FatJar load(ClassLoader loader) throws ClassNotFoundException, IOException {
    InputStream inputStream = loader.getResourceAsStream(FAT_JAR_INFO_RESOURCE);
    try {
      BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
      try {
        ObjectInputStream objectInputStream = new ObjectInputStream(bufferedInputStream);
        try {
          return (FatJar) objectInputStream.readObject();
        } finally {
          objectInputStream.close();
        }
      } finally {
        bufferedInputStream.close();
      }
    } finally {
      inputStream.close();
    }
  }

  /** Serialize this instance as binary to {@code outputStream}. */
  public void store(OutputStream outputStream) throws IOException {
    ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
    try {
      // Explicitly specify a protocol version, just in case the default protocol gets updated with
      // a new version of Java. We need to ensure the serialized data can be read by older versions
      // of Java, as the fat jar stub, which references this class, is compiled against an older
      // version of Java for compatibility purposes, unlike the main Buck jar, which also references
      // this class.
      objectOutputStream.useProtocolVersion(ObjectStreamConstants.PROTOCOL_VERSION_2);

      objectOutputStream.writeObject(this);
    } finally {
      objectOutputStream.close();
    }
  }

  public void unpackNativeLibrariesInto(ClassLoader loader, Path destination) throws IOException {
    for (Map.Entry<String, String> entry : Preconditions.checkNotNull(nativeLibraries).entrySet()) {
      InputStream input = loader.getResourceAsStream(entry.getValue());
      try {
        BufferedInputStream bufferedInput = new BufferedInputStream(input);
        try {
          Files.copy(bufferedInput, destination.resolve(entry.getKey()));
        } finally {
          bufferedInput.close();
        }
      } finally {
        input.close();
      }
    }
  }

  public void unpackJarTo(ClassLoader loader, Path destination) throws IOException {
    InputStream input = loader.getResourceAsStream(Preconditions.checkNotNull(innerJar));
    try {
      BufferedInputStream bufferedInput = new BufferedInputStream(input);
      try {
        Files.copy(bufferedInput, destination);
      } finally {
        bufferedInput.close();
      }
    } finally {
      input.close();
    }
  }
}

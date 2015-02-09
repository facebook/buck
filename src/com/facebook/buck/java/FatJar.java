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

/***************
 *
 * This code can be embedded in arbitrary third-party projects!
 * For maximum compatibility, use only Java 6 constructs.
 *
 ***************/

package com.facebook.buck.java;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;

@XmlRootElement(name = "fatjar")
@XmlAccessorType(XmlAccessType.FIELD)
public class FatJar {
  /**
   * Since FatJar is going to be embedded in many targets, it cannot have external dependencies, but
   * we'd like to have {@link javax.annotation.Nullable} and
   * {@link com.google.common.base.Preconditions#checkNotNull} anyway, so we define these here.
   */
  @interface Nullable {}
  private static class Preconditions {
    private Preconditions() {}

    public static <T> T checkNotNull(@Nullable T value) {
      if (value == null) {
        throw new RuntimeException();
      }
      return value;
    }
  }

  public static final String FAT_JAR_INFO_RESOURCE = "fat_jar_info.dat";

  /**
   * The resource name for the real JAR.
   */
  @Nullable
  private String innerJar;

  /**
   * The map of system-specific shared library names to their corresponding resource names.
   */
  @Nullable
  private Map<String, String> nativeLibraries;

  // Required for XML deserialization.
  protected FatJar() {}

  public FatJar(String innerJar, Map<String, String> nativeLibraries) {
    this.innerJar = innerJar;
    this.nativeLibraries = nativeLibraries;
  }

  /**
   * @return the {@link FatJar} object deserialized from the resource name via {@code loader}.
   */
  public static FatJar load(ClassLoader loader) throws Exception {
    InputStream inputStream = loader.getResourceAsStream(FAT_JAR_INFO_RESOURCE);
    try {
      BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
      try {
        XMLEventReader xmlEventReader =
            XMLInputFactory.newFactory().createXMLEventReader(bufferedInputStream);
        JAXBContext context = JAXBContext.newInstance(FatJar.class);
        Unmarshaller unmarshaller = context.createUnmarshaller();
        JAXBElement<FatJar> jaxbElementA = unmarshaller.unmarshal(xmlEventReader, FatJar.class);
        return jaxbElementA.getValue();
      } finally {
        bufferedInputStream.close();
      }
    } finally {
      inputStream.close();
    }
  }

  /**
   * Serialize this instance as XML to {@code outputStream}.
   */
  public void store(OutputStream outputStream) throws Exception {
    JAXBContext context = JAXBContext.newInstance(FatJar.class);
    JAXBElement<FatJar> element = new JAXBElement<FatJar>(new QName("fatjar"), FatJar.class, this);
    Marshaller marshaller = context.createMarshaller();
    marshaller.marshal(element, outputStream);
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

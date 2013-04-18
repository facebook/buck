/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

public class KeystoreProperties {

  private final String keystore;
  private final String storepass;
  private final String keypass;
  private final String alias;

  private KeystoreProperties(String keystore, String storepass, String keypass, String alias) {
    this.keystore = Preconditions.checkNotNull(keystore);
    this.storepass = Preconditions.checkNotNull(storepass);
    this.keypass = Preconditions.checkNotNull(keypass);
    this.alias = Preconditions.checkNotNull(alias);
  }

  public String getKeystore() {
    return keystore;
  }

  public String getStorepass() {
    return storepass;
  }

  public String getKeypass() {
    return keypass;
  }

  public String getAlias() {
    return alias;
  }

  public static KeystoreProperties createFromPropertiesFile(String pathToKeystorePropertiesFile,
      ProjectFilesystem projectFilesystem) throws IOException {
    Properties properties = projectFilesystem.readPropertiesFile(pathToKeystorePropertiesFile);

    String keystore = getOrThrowException(
        properties, "key.store", pathToKeystorePropertiesFile);
    String keystorePassword = getOrThrowException(
        properties, "key.store.password", pathToKeystorePropertiesFile);
    String alias = getOrThrowException(properties, "key.alias", pathToKeystorePropertiesFile);
    String aliasPassword = getOrThrowException(
        properties, "key.alias.password", pathToKeystorePropertiesFile);

    // keystore is a path relative to the properties file, so resolve it before passing it to the
    // SignApkCommand.
    File keystorePropertiesFile = new File(pathToKeystorePropertiesFile);
    File keystoreDirectory = keystorePropertiesFile.getParentFile();
    File keystoreFile = new File(keystoreDirectory, keystore);

    return new KeystoreProperties(keystoreFile.getPath(), keystorePassword, aliasPassword, alias);
  }

  /**
   * @return a non-null, non-empty value for the specified property
   * @throws HumanReadableException if there is no value for the specified property
   */
  private static String getOrThrowException(Properties properties,
      String propertyName,
      String pathToKeystorePropertiesFile) {
    String value = Strings.nullToEmpty(properties.getProperty(propertyName)).trim();
    if (value.isEmpty()) {
      throw new HumanReadableException(
          "properties file %s did not contain a value for the property %s",
          pathToKeystorePropertiesFile,
          propertyName);
    } else {
      return value;
    }
  }

}

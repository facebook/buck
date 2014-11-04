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

import com.google.common.base.Strings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

public class KeystoreProperties {

  private final Path keystore;
  private final String storepass;
  private final String keypass;
  private final String alias;

  public KeystoreProperties(Path keystore, String storepass, String keypass, String alias) {
    this.keystore = keystore;
    this.storepass = storepass;
    this.keypass = keypass;
    this.alias = alias;
  }

  public Path getKeystore() {
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

  public static KeystoreProperties createFromPropertiesFile(
      Path pathToStore,
      Path pathToKeystorePropertiesFile,
      ProjectFilesystem projectFilesystem) throws IOException {
    Properties properties = projectFilesystem.readPropertiesFile(pathToKeystorePropertiesFile);

    String keystorePassword = getOrThrowException(
        properties, "key.store.password", pathToKeystorePropertiesFile);
    String alias = getOrThrowException(properties, "key.alias", pathToKeystorePropertiesFile);
    String aliasPassword = getOrThrowException(
        properties, "key.alias.password", pathToKeystorePropertiesFile);

    return new KeystoreProperties(pathToStore, keystorePassword, aliasPassword, alias);
  }

  /**
   * @return a non-null, non-empty value for the specified property
   * @throws HumanReadableException if there is no value for the specified property
   */
  private static String getOrThrowException(Properties properties,
      String propertyName,
      Path pathToKeystorePropertiesFile) {
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

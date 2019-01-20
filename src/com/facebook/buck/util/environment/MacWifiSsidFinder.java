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

package com.facebook.buck.util.environment;

import ca.weblite.objc.Client;
import ca.weblite.objc.Proxy;
import ca.weblite.objc.RuntimeUtils;
import com.facebook.buck.core.util.log.Logger;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.util.Optional;

/** Mac OS X implementation of finding the SSID of the default Wi-Fi interface. */
public class MacWifiSsidFinder {
  private static final Logger LOG = Logger.get(MacWifiSsidFinder.class);

  // Utility class, do not instantiate.
  private MacWifiSsidFinder() {}

  public interface CoreWlan extends Library {}

  // Need to hold on to an instance of this library so CoreWLAN.framework is kept resident.
  public static final CoreWlan CORE_WLAN_INSTANCE = Native.loadLibrary("CoreWLAN", CoreWlan.class);

  /** Finds the SSID of the default Wi-Fi interface using Mac OS X APIs. */
  public static Optional<String> findCurrentSsid() {
    LOG.debug("Getting current SSID..");

    // Grab a handle to the Objective-C runtime.
    Client objcClient = Client.getInstance();

    // Try the OS X 10.10 and later supported API, then fall
    // back to the OS X 10.6 API.
    Pointer wifiClientClass = RuntimeUtils.cls("CWWiFiClient");
    Optional<Proxy> defaultInterface;
    if (wifiClientClass != null) {
      LOG.verbose("Getting default interface using +[CWWiFiClient sharedWiFiClient]");
      defaultInterface = getDefaultWifiInterface(objcClient, wifiClientClass);
    } else {
      LOG.verbose("Getting default interface using +[CWInterface defaultInterface]");
      // CWInterface *defaultInterface = [CWInterface interface];
      defaultInterface = Optional.ofNullable(objcClient.sendProxy("CWInterface", "interface"));
    }
    return getSsidFromInterface(defaultInterface);
  }

  /** Finds the SSID of the default Wi-Fi interface using Mac OS X 10.10 and later APIs. */
  private static Optional<Proxy> getDefaultWifiInterface(
      Client objcClient, Pointer wifiClientClass) {
    // CWWiFiClient *sharedWiFiClient = [CWWiFiClient sharedWiFiClient];
    Proxy sharedWiFiClient = objcClient.sendProxy(wifiClientClass, "sharedWiFiClient");
    if (sharedWiFiClient == null) {
      LOG.warn("+[CWWiFiClient sharedWiFiClient] returned null, could not find SSID.");
      return Optional.empty();
    }

    // CWInterface *defaultInterface = [sharedWiFiClient interface];
    Proxy defaultInterface = sharedWiFiClient.sendProxy("interface");
    if (defaultInterface == null) {
      LOG.warn("-[sharedWiFiClient interface] returned null, could not find SSID.");
      return Optional.empty();
    }

    return Optional.of(defaultInterface);
  }

  private static Optional<String> getSsidFromInterface(Optional<Proxy> defaultInterface) {
    if (!defaultInterface.isPresent()) {
      LOG.debug("No Wi-Fi interface found.");
      return Optional.empty();
    }
    LOG.debug("Getting SSID from Wi-Fi interface: %s", defaultInterface.get());

    // NSString *ssid = [defaultInterface ssid];
    Object ssid = defaultInterface.get().send("ssid");
    if (ssid == null) {
      LOG.debug("No SSID found for interface %s.", defaultInterface.get());
      return Optional.empty();
    }
    String ssidString;
    if (!(ssid instanceof String)) {
      LOG.warn(
          "Fetched SSID, but got unexpected non-string type (got: %s).", ssid.getClass().getName());
      ssidString = ssid.toString();
    } else {
      ssidString = (String) ssid;
    }

    LOG.debug("Found SSID: %s", ssidString);
    return Optional.of(ssidString);
  }
}

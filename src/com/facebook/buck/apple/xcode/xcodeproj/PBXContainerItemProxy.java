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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.google.common.base.Preconditions;

/**
 * Reference to another target used by {@link PBXTargetDependency}. Can reference a target
 * in a remote project file.
 */
public class PBXContainerItemProxy extends PBXContainerItem {
  public enum ProxyType {
    TARGET_REFERENCE(1);

    private final int intValue;
    private ProxyType(int intValue) {
      this.intValue = intValue;
    }

    public int getIntValue() {
      return intValue;
    }
  }

  private final PBXProject containerPortal;
  private final PBXTarget target;
  private final ProxyType proxyType;

  public PBXContainerItemProxy(
      PBXProject containerPortal,
      PBXTarget target) {
    this.containerPortal = containerPortal;
    this.target = target;
    this.proxyType = ProxyType.TARGET_REFERENCE;
  }

  public PBXProject getContainerPortal() {
    return containerPortal;
  }

  public PBXTarget getTarget() {
    return target;
  }

  public ProxyType getProxyType() {
    return proxyType;
  }

  @Override
  public String isa() {
    return "PBXContainerItemProxy";
  }

  @Override
  public int stableHash() {
    return containerPortal.hashCode() ^ target.hashCode();
  }

  @Override
  public void serializeInto(XcodeprojSerializer s) {
    super.serializeInto(s);

    Preconditions.checkNotNull(containerPortal.getGlobalID());
    s.addField("containerPortal", containerPortal.getGlobalID());
    s.addField("remoteGlobalIDString", target);
    s.addField("remoteInfo", target.getName());
    s.addField("proxyType", proxyType.getIntValue());
  }
}

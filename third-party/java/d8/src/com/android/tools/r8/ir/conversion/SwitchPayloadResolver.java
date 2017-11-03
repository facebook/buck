// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.code.SwitchPayload;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper class for resolving payload information during IR construction.
 */
public class SwitchPayloadResolver {

  public static class PayloadData {

    public final static int NO_SIZE = -1;
    public int userOffset;
    public int[] absoluteTargets = null;
    public int[] keys = null;
    public int size = NO_SIZE;

    public PayloadData(int userOffset) {
      this.userOffset = userOffset;
    }
  }

  private final Map<Integer, SwitchPayload> unresolvedPayload = new HashMap<>();
  private final Map<Integer, PayloadData> payloadToData = new HashMap<>();

  public void addPayloadUser(Instruction dex) {
    int offset = dex.getOffset();
    int payloadOffset = offset + dex.getPayloadOffset();
    payloadToData.put(payloadOffset, new PayloadData(offset));
    if (unresolvedPayload.containsKey(payloadOffset)) {
      SwitchPayload payload = unresolvedPayload.remove(payloadOffset);
      resolve(payload);
    }
  }

  public void resolve(SwitchPayload payload) {
    int payloadOffset = payload.getOffset();
    PayloadData data = payloadToData.get(payloadOffset);
    if (data == null) {
      unresolvedPayload.put(payloadOffset, payload);
      return;
    }

    int[] targets = payload.switchTargetOffsets();
    int[] absoluteTargets = new int[targets.length];
    for (int i = 0; i < targets.length; i++) {
      absoluteTargets[i] = data.userOffset + targets[i];
    }
    data.absoluteTargets = absoluteTargets;
    data.keys = payload.keys();
    data.size = payload.numberOfKeys();
  }

  public int[] absoluteTargets(Instruction dex) {
    assert dex.isSwitch();
    return absoluteTargets(dex.getOffset() + dex.getPayloadOffset());
  }

  public int[] absoluteTargets(int payloadOffset) {
    return payloadToData.get(payloadOffset).absoluteTargets;
  }

  public int[] getKeys(int payloadOffset) {
    return payloadToData.get(payloadOffset).keys;
  }

  public int getSize(int payloadOffset) {
    return payloadToData.get(payloadOffset).size;
  }

  public Collection<PayloadData> payloadDataSet() {
    return payloadToData.values();
  }

  public void clear() {
    payloadToData.clear();
  }
}

/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dx.util;

import com.android.dex.util.ByteArrayByteInput;
import com.android.dex.util.ByteInput;
import com.android.dex.Mutf8;
import java.io.IOException;
import java.util.Arrays;
import junit.framework.TestCase;

public final class Mutf8Test extends TestCase {

    public void testDecode() throws IOException {
        ByteInput in = new ByteArrayByteInput(
                new byte[] { 'A', 'B', 'C', (byte) 0xc0, (byte) 0x80, 0, 'E' });
        assertEquals('A', in.readByte());
        assertEquals("BC\u0000", Mutf8.decode(in, new char[3]));
        assertEquals('E', in.readByte());
    }

    public void testEncode() throws IOException {
        assertEquals(Arrays.toString(new byte[] { 'B', 'C', (byte) 0xc0, (byte) 0x80 }),
                Arrays.toString(Mutf8.encode("BC\u0000")));
    }
}

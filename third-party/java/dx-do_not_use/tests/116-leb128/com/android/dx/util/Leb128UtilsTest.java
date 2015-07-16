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
import com.android.dex.Leb128;
import java.io.IOException;
import java.util.Arrays;
import junit.framework.TestCase;

public final class Leb128UtilsTest extends TestCase {

    public void testDecodeUnsignedLeb() throws IOException {
        assertEquals(0, Leb128.readUnsignedLeb128(new ByteArrayByteInput((byte) 0)));
        assertEquals(1, Leb128.readUnsignedLeb128(new ByteArrayByteInput((byte) 1)));
        assertEquals(127, Leb128.readUnsignedLeb128(new ByteArrayByteInput((byte) 0x7f)));
        assertEquals(16256, Leb128.readUnsignedLeb128(
                new ByteArrayByteInput((byte) 0x80, (byte) 0x7f)));
    }

    public void testEncodeUnsignedLeb() throws IOException {
        assertEquals(new byte[] { 0 }, encodeUnsignedLeb(0));
        assertEquals(new byte[] { 1 }, encodeUnsignedLeb(1));
        assertEquals(new byte[] { 0x7f }, encodeUnsignedLeb(127));
        assertEquals(new byte[] { (byte) 0x80, 0x7f }, encodeUnsignedLeb(16256));
        assertEquals(new byte[] { (byte) 0xb4, 0x07 }, encodeUnsignedLeb(0x3b4));
        assertEquals(new byte[] { (byte) 0x8c, 0x08 }, encodeUnsignedLeb(0x40c));
        assertEquals(new byte[] { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0xf },
                encodeUnsignedLeb(0xffffffff));
    }

    public void testDecodeSignedLeb() throws IOException {
        assertEquals(0, Leb128.readSignedLeb128(new ByteArrayByteInput((byte) 0)));
        assertEquals(1, Leb128.readSignedLeb128(new ByteArrayByteInput((byte) 1)));
        assertEquals(-1, Leb128.readSignedLeb128(new ByteArrayByteInput((byte) 0x7f)));
        assertEquals(0x3c, Leb128.readSignedLeb128(new ByteArrayByteInput((byte) 0x3c)));
        assertEquals(-128, Leb128.readSignedLeb128(
                new ByteArrayByteInput((byte) 0x80, (byte) 0x7f)));
    }

    public void testEncodeSignedLeb() throws IOException {
        assertEquals(new byte[] { 0 }, encodeSignedLeb(0));
        assertEquals(new byte[] { 1 }, encodeSignedLeb(1));
        assertEquals(new byte[] { 0x7f }, encodeSignedLeb(-1));
        assertEquals(new byte[] { (byte) 0x80, 0x7f }, encodeSignedLeb(-128));
    }

    private byte[] encodeSignedLeb(int value) {
        ByteArrayAnnotatedOutput out = new ByteArrayAnnotatedOutput(5);
        Leb128.writeSignedLeb128(out, value);
        return out.toByteArray();
    }

    private byte[] encodeUnsignedLeb(int value) {
        ByteArrayAnnotatedOutput out = new ByteArrayAnnotatedOutput(5);
        Leb128.writeUnsignedLeb128(out, value);
        return out.toByteArray();
    }

    private void assertEquals(byte[] expected, byte[] actual) {
        assertTrue(Arrays.toString(actual), Arrays.equals(expected, actual));
    }
}

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

package com.android.dx.merge;

import com.android.dex.Dex;
import com.android.dex.DexException;
import java.io.IOException;
import junit.framework.TestCase;

public final class MergeConflictTest extends TestCase {

    public void testMergeConflict() throws IOException {
        Dex a = resourceToDexBuffer("/testdata/A.dex");
        Dex b = resourceToDexBuffer("/testdata/B.dex");

        // a and b don't overlap; this should succeed
        Dex ab = new DexMerger(a, b, CollisionPolicy.FAIL).merge();

        // a and ab overlap; this should fail
        DexMerger dexMerger = new DexMerger(a, ab, CollisionPolicy.FAIL);
        try {
            dexMerger.merge();
            fail();
        } catch (DexException expected) {
            assertEquals("Multiple dex files define Ltestdata/A;", expected.getMessage());
        }
    }

    private Dex resourceToDexBuffer(String resource) throws IOException {
        return new Dex(getClass().getResourceAsStream(resource));
    }
}

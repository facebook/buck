/*
 * Copyright (C) 2007 The Android Open Source Project
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

public class Blort
{
    public boolean insBoolean;
    public byte insByte;
    public char insChar;
    public short insShort;
    public int insInt;
    public long insLong;
    public float insFloat;
    public double insDouble;
    public Object insObject;

    public Object test1() {
        int x = insByte + insChar + insShort + insInt +
            (int) insLong + (int) insFloat + (int) insDouble;

        if (insBoolean && (x > 0)) {;
            return insObject;
        } else {
            return null;
        }
    }

    public void test2(boolean b, int i, Object o) {
        insBoolean = b;
        insByte = (byte) i;
        insChar = (char) i;
        insShort = (short) i;
        insInt = i;
        insLong = i;
        insFloat = i;
        insDouble = i;
        insObject = o;
    }
}

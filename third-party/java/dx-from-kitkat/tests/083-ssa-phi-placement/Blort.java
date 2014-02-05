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

    public int phiTest() {
        int i = 1;
        int j = 1;
        int k = 0;

        while (k < 100) {
            if (j < 20) {
                j = i;
                k++;
            } else {
                j = k;
                k += 2;
            }
        }

        return j;
    }

    /**
     * This method uses no registers.
     */
    public static void noVars() {
    }

    /**
     * This method requires an ordered successor list with
     * multiple identically-valued entries.
     */
    Object fd;
    public Object getOption(int optID) throws RuntimeException
    {
        if (fd == null) {
            throw new RuntimeException("socket not created");
        }

        int value = 0;
        switch (optID)
        {
            case 1:
            case 2:
                return new Integer(value);
            case 3:
            default:
                return Boolean.valueOf(value != 0);
        }
    }
}

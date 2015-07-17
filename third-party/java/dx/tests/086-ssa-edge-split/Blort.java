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
    /**
     * This method requires the edge-splitter to add a node
     * to get to the finally block, since there are
     * two exception sources.
     *
     */
    public int edgeSplitPredTest(int x) {
        int y = 1;

        try {
            Integer.toString(x);
            Integer.toString(x);
            y++;
        } finally {
            return y;
        }
    }

    /**
     * just because this should do nothing
     */
    void voidFunction() {
    }

    /**
     * Current SSA form requires each move-exception block to have
     * a unique predecessor
     */
    void edgeSplitMoveException() {
        try {
            hashCode();
            hashCode();
        } catch (Throwable tr) {
        }
    }

    /**
     * Presently, any basic block ending in an instruction with
     * a result needs to have a unique successor. This appies
     * only to the block between the switch instruction and the return
     * in this case.
     */
    int edgeSplitSuccessor(int x) {
        int y = 0;

        switch(x) {
            case 1: y++;
            break;
            case 2: y++;
            break;
            case 3: y++;
            break;
        }
        return y;
    }
}

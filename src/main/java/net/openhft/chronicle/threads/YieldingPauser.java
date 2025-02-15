/*
 * Copyright 2016-2020 chronicle.software
 *
 * https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;

public class YieldingPauser implements Pauser {
    private final int minBusy;
    private int count = 0;
    private long timePaused = 0;
    private long countPaused = 0;
    private long yieldStart = 0;

    /**
     * first it will busy wait, then it will yield.
     *
     * @param minBusy the min number of times it will go around doing nothing, after this is
     *                reached it will then start to yield
     */
    public YieldingPauser(int minBusy) {
        this.minBusy = minBusy;
    }

    @Override
    public void reset() {
        checkYieldTime();
        count = 0;
    }

    @Override
    public void pause() {
        ++count;
        if (count < minBusy) {
            Jvm.safepoint();
            return;
        }
        yield0();
    }

    private void checkYieldTime() {
        if (yieldStart > 0) {
            long time = System.nanoTime() - yieldStart;
            timePaused += time;
            countPaused++;
            yieldStart = 0;
        }
    }

    private void yield0() {
        if (yieldStart == 0)
            yieldStart = System.nanoTime();
        Thread.yield();
    }

    @Override
    public void unpause() {
        // Do nothing
    }

    @Override
    public long timePaused() {
        return timePaused / 1_000_000;
    }

    @Override
    public long countPaused() {
        return countPaused;
    }
}

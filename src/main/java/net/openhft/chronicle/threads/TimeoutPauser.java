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
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TimeoutPauser implements Pauser, TimingPauser {
    private final int minBusy;
    private int count = 0;
    private long timePaused = 0;
    private long countPaused = 0;
    private long yieldStart = 0;
    private long timeOutStart = Long.MAX_VALUE;

    /**
     * first it will busy wait, then it will yield.
     *
     * @param minBusy the min number of times it will go around doing nothing, after this is reached it will then start to yield
     */
    public TimeoutPauser(int minBusy) {
        this.minBusy = minBusy;
    }

    @Override
    public void reset() {
        checkYieldTime();
        count = 0;
        timeOutStart = Long.MAX_VALUE;
    }

    @Override
    public void pause() {
        ++count;
        if (count < minBusy) {
            Jvm.nanoPause();
            return;
        }

        yield0();
        checkYieldTime();
    }

    @Override
    public void pause(long timeout, @NotNull TimeUnit timeUnit) throws TimeoutException {
        if (timeOutStart == Long.MAX_VALUE)
            timeOutStart = System.nanoTime();

        ++count;
        if (count < minBusy)
            return;
        yield0();

        if (timeOutStart + timeUnit.toNanos(timeout) - System.nanoTime() < 0)
            throw new TimeoutException();
        checkYieldTime();
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

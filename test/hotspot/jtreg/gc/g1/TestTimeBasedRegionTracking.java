/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package gc.g1;

/**
 * @test TestTimeBasedRegionTracking
 * @bug 8357445
 * @summary Test region activity tracking and state transitions for time-based heap sizing
 * @requires vm.gc.G1
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management/sun.management
 * @run main/othervm/timeout=120 -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions
 *      -Xms32m -Xmx128m -XX:G1HeapRegionSize=1M
 *      -XX:G1TimeBasedEvaluationIntervalMillis=5000
 *      -XX:G1UncommitDelayMillis=10000
 *      -XX:G1MinRegionsToUncommit=2
 *      -Xlog:gc*,gc+sizing*=debug gc.g1.TestTimeBasedRegionTracking
 */

import java.util.*;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import java.util.concurrent.atomic.AtomicBoolean;

public class TestTimeBasedRegionTracking {

    static ProcessBuilder createSubprocess(String testClass) {
        return ProcessTools.createTestJavaProcessBuilder(
            "-XX:+UseG1GC",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:G1TimeBasedEvaluationIntervalMillis=5000",
            "-XX:G1UncommitDelayMillis=10000",
            "-XX:G1MinRegionsToUncommit=2",
            "-XX:G1HeapRegionSize=1M",
            "-Xmx128m", "-Xms32m",
            "-Xlog:gc*,gc+sizing*=debug",
            testClass
        );
    }

    public static void main(String[] args) throws Exception {
        testRegionStateTransitions();
        testConcurrentRegionAccess();
        testRegionLifecycleEdgeCases();
        testSafepointRaceConditions();
    }

    static void testRegionStateTransitions() throws Exception {
        ProcessBuilder pb = createSubprocess(
            "gc.g1.TestTimeBasedRegionTracking$RegionTransitionTest");
        OutputAnalyzer output = new OutputAnalyzer(pb.start());

        output.shouldContain("Starting uncommit evaluation");
        output.shouldHaveExitValue(0);
    }

    public static class RegionTransitionTest {
        private static final int MB = 1024 * 1024;
        private static ArrayList<byte[]> arrays = new ArrayList<>();

        public static void main(String[] args) throws Exception {
            allocateMemory(20);
            System.gc();

            // Idle period - wait for evaluation + uncommit delay.
            arrays.clear();
            System.gc();
            Thread.sleep(12000);

            // Reallocate to exercise region reuse.
            allocateMemory(10);
            System.gc();

            arrays = null;
            System.gc();
            Thread.sleep(1000);

            Runtime.getRuntime().halt(0);
        }

        static void allocateMemory(int mb) throws InterruptedException {
            for (int i = 0; i < mb; i++) {
                arrays.add(new byte[MB]);
                if (i % 4 == 0) Thread.sleep(10);
            }
        }
    }

    static void testConcurrentRegionAccess() throws Exception {
        ProcessBuilder pb = createSubprocess(
            "gc.g1.TestTimeBasedRegionTracking$ConcurrentAccessTest");
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldHaveExitValue(0);
    }

    static void testRegionLifecycleEdgeCases() throws Exception {
        ProcessBuilder pb = createSubprocess(
            "gc.g1.TestTimeBasedRegionTracking$RegionLifecycleEdgeCaseTest");
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldHaveExitValue(0);
    }

    static void testSafepointRaceConditions() throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
            "-XX:+UseG1GC",
            "-XX:+UnlockDiagnosticVMOptions",
            "-Xms64m", "-Xmx256m",
            "-XX:G1HeapRegionSize=1M",
            "-XX:G1TimeBasedEvaluationIntervalMillis=5000",
            "-XX:G1UncommitDelayMillis=3000",
            "-XX:G1MinRegionsToUncommit=1",
            "-Xlog:gc*,gc+sizing*=debug",
            "gc.g1.TestTimeBasedRegionTracking$SafepointRaceTest"
        );

        OutputAnalyzer output = new OutputAnalyzer(pb.start());

        output.shouldContain("G1 Time-Based Heap Sizing enabled (uncommit-only)");
        output.shouldHaveExitValue(0);
    }

    public static class ConcurrentAccessTest {
        private static final int MB = 1024 * 1024;
        private static final List<byte[]> sharedMemory = new ArrayList<>();
        private static volatile boolean stopThreads = false;

        public static void main(String[] args) throws Exception {
            Thread[] threads = new Thread[3];
            for (int t = 0; t < threads.length; t++) {
                final int threadId = t;
                threads[t] = new Thread(() -> {
                    int iterations = 0;
                    while (!stopThreads && iterations < 30) {
                        try {
                            // Allocate
                            for (int i = 0; i < 3; i++) {
                                synchronized (sharedMemory) {
                                    sharedMemory.add(new byte[512 * 1024]); // 512KB
                                }
                                Thread.sleep(10);
                            }

                            // Clear some memory
                            synchronized (sharedMemory) {
                                if (sharedMemory.size() > 15) {
                                    for (int i = 0; i < 5; i++) {
                                        if (!sharedMemory.isEmpty()) {
                                            sharedMemory.remove(0);
                                        }
                                    }
                                }
                            }

                            if (iterations % 10 == 0) {
                                System.gc();
                            }

                            iterations++;
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    System.out.println("Thread " + threadId + " completed " + iterations + " iterations");
                });
                threads[t].start();
            }

            Thread.sleep(5000);

            stopThreads = true;
            for (Thread t : threads) {
                t.join(1000);
            }

            synchronized (sharedMemory) {
                sharedMemory.clear();
            }
            System.gc();
            Thread.sleep(1000);

            Runtime.getRuntime().halt(0);
        }
    }

    public static class RegionLifecycleEdgeCaseTest {
        private static final int MB = 1024 * 1024;
        private static List<Object> memory = new ArrayList<>();

        public static void main(String[] args) throws Exception {
            // Mixed allocation patterns: small, medium, large (not humongous).
            for (int i = 0; i < 100; i++) {
                memory.add(new byte[8 * 1024]);
            }
            for (int i = 0; i < 20; i++) {
                memory.add(new byte[40 * 1024]);
            }
            for (int i = 0; i < 5; i++) {
                memory.add(new byte[300 * 1024]);
            }

            Thread.sleep(2000);

            // Create fragmentation by selective deallocation.
            for (int i = memory.size() - 1; i >= 0; i -= 2) {
                memory.remove(i);
            }

            System.gc();
            Thread.sleep(3000);

            // Add humongous objects (> 512KB for 1MB regions).
            for (int i = 0; i < 3; i++) {
                memory.add(new byte[900 * 1024]);
                Thread.sleep(500);
            }

            Thread.sleep(2000);

            memory.clear();
            System.gc();
            Thread.sleep(6000);

            Runtime.getRuntime().halt(0);
        }
    }

    public static class SafepointRaceTest {
        public static void main(String[] args) throws Exception {
            final AtomicBoolean stopFlag = new AtomicBoolean(false);
            final List<byte[]> sharedMemory = Collections.synchronizedList(new ArrayList<>());

            Thread[] threads = new Thread[2];
            for (int i = 0; i < threads.length; i++) {
                final int threadId = i;
                threads[i] = new Thread(() -> {
                    int iteration = 0;
                    while (!stopFlag.get() && iteration < 10) {
                        try {
                            for (int j = 0; j < 3; j++) {
                                sharedMemory.add(new byte[256 * 1024]);
                            }

                            if (iteration % 5 == 0) {
                                System.gc();
                            }

                            // Clear some allocations
                            synchronized (sharedMemory) {
                                if (sharedMemory.size() > 6) {
                                    for (int k = 0; k < 2; k++) {
                                        if (!sharedMemory.isEmpty()) {
                                            sharedMemory.remove(0);
                                        }
                                    }
                                }
                            }

                            Thread.sleep(50);
                            iteration++;
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                });
                threads[i].start();
            }

            Thread.sleep(4000);

            stopFlag.set(true);
            for (Thread thread : threads) {
                thread.join(1000);
            }

            sharedMemory.clear();
            System.gc();
            Thread.sleep(2000);

            Runtime.getRuntime().halt(0);
        }
    }
}

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
 * @test TestG1RegionUncommit
 * @requires vm.gc.G1
 * @summary Test that G1 uncommits regions based on time threshold
 * @bug 8357445
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management/sun.management
 * @run main/othervm -XX:+UseG1GC -Xms8m -Xmx256m -XX:G1HeapRegionSize=1M
 *                   -XX:+UnlockDiagnosticVMOptions
 *                   -XX:G1UncommitDelayMillis=3000 -XX:G1TimeBasedEvaluationIntervalMillis=2000
 *                   -XX:G1MinRegionsToUncommit=2
 *                   -Xlog:gc*,gc+sizing*=debug
 *                   gc.g1.TestG1RegionUncommit
 */

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestG1RegionUncommit {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            testTimeBasedEvaluation();
            testMinimumHeapBoundary();
            testConcurrentAllocationUncommit();
        } else if ("subprocess".equals(args[0])) {
            runTimeBasedUncommitTest();
        } else if ("minheap".equals(args[0])) {
            runMinHeapBoundaryTest();
        } else if ("concurrent".equals(args[0])) {
            runConcurrentTest();
        }
    }

    static long extractValue(String stdout, String prefix) {
        for (String line : stdout.split("\n")) {
            if (line.contains(prefix)) {
                return Long.parseLong(line.substring(
                    line.indexOf(prefix) + prefix.length()).trim());
            }
        }
        throw new RuntimeException(
            "Could not find '" + prefix + "' in subprocess output");
    }

    static void testTimeBasedEvaluation() throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
            "-XX:+UseG1GC",
            "-Xms8m", "-Xmx256m", "-XX:G1HeapRegionSize=1M",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:G1UncommitDelayMillis=3000", "-XX:G1TimeBasedEvaluationIntervalMillis=2000",
            "-XX:G1MinRegionsToUncommit=2",
            "-Xlog:gc*,gc+sizing*=debug",
            "gc.g1.TestG1RegionUncommit", "subprocess"
        );

        OutputAnalyzer output = new OutputAnalyzer(pb.start());

        output.shouldContain("G1 Time-Based Heap Sizing enabled (uncommit-only)");
        output.shouldContain("Starting uncommit evaluation");
        output.shouldContain("Uncommit evaluation:");

        output.shouldHaveExitValue(0);
    }

    static void runTimeBasedUncommitTest() throws Exception {
        final int allocSize = 64 * 1024 * 1024;
        Object keepAlive;
        Object keepAlive2;

        // Allocate to force heap expansion.
        keepAlive = new byte[allocSize];

        // Keep 24MB allocated, free the 64MB to create idle regions.
        keepAlive2 = new byte[24 * 1024 * 1024];
        keepAlive = null;
        System.gc();
        System.gc();

        // Wait for uncommit delay (3s) + evaluation interval (2s) + margin.
        Thread.sleep(15000);

        keepAlive2 = null;
        System.gc();

        Runtime.getRuntime().halt(0);
    }

    static void testMinimumHeapBoundary() throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
            "-XX:+UseG1GC",
            "-Xms32m", "-Xmx64m",
            "-XX:G1HeapRegionSize=1M",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:G1UncommitDelayMillis=2000",
            "-XX:G1TimeBasedEvaluationIntervalMillis=1000",
            "-XX:G1MinRegionsToUncommit=1",
            "-Xlog:gc+sizing=debug,gc+task=debug",
            "gc.g1.TestG1RegionUncommit", "minheap"
        );

        OutputAnalyzer output = new OutputAnalyzer(pb.start());

        // Committed memory must not drop below Xms.
        long committed = extractValue(output.getStdout(), "HEAP_COMMITTED_FINAL=");
        long xms = 32L * 1024 * 1024;
        if (committed < xms) {
            throw new RuntimeException(
                "Committed memory " + committed + " dropped below Xms " + xms);
        }

        output.shouldHaveExitValue(0);
    }

    static void testConcurrentAllocationUncommit() throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
            "-XX:+UseG1GC",
            "-Xms64m", "-Xmx256m",
            "-XX:G1HeapRegionSize=1M",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:G1TimeBasedEvaluationIntervalMillis=1000",
            "-XX:G1UncommitDelayMillis=2000",
            "-XX:G1MinRegionsToUncommit=2",
            "-Xlog:gc+sizing=debug,gc+task=debug",
            "gc.g1.TestG1RegionUncommit", "concurrent"
        );

        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldHaveExitValue(0);
    }

    static void runMinHeapBoundaryTest() throws Exception {
        List<byte[]> memory = new ArrayList<>();

        for (int i = 0; i < 28; i++) {
            memory.add(new byte[1024 * 1024]);
        }

        memory.clear();
        System.gc();
        // Wait longer than uncommit delay (2s) + evaluation interval (1s).
        Thread.sleep(8000);

        long committed = ManagementFactory.getMemoryMXBean()
            .getHeapMemoryUsage().getCommitted();
        System.out.println("HEAP_COMMITTED_FINAL=" + committed);

        Runtime.getRuntime().halt(0);
    }

    static void runConcurrentTest() throws Exception {
        final List<byte[]> sharedMemory = new ArrayList<>();
        final boolean[] stopFlag = {false};

        Thread allocThread = new Thread(() -> {
            int iterations = 0;
            while (!stopFlag[0] && iterations < 50) {
                try {
                    // Allocate
                    for (int j = 0; j < 5; j++) {
                        synchronized (sharedMemory) {
                            sharedMemory.add(new byte[1024 * 1024]); // 1MB
                        }
                        Thread.sleep(10);
                    }

                    // Clear some
                    synchronized (sharedMemory) {
                        if (sharedMemory.size() > 10) {
                            for (int k = 0; k < 5; k++) {
                                if (!sharedMemory.isEmpty()) {
                                    sharedMemory.remove(0);
                                }
                            }
                        }
                    }
                    System.gc();
                    Thread.sleep(50);
                    iterations++;
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        allocThread.start();

        // Let it run for a while to trigger time-based evaluation
        Thread.sleep(8000);

        stopFlag[0] = true;
        allocThread.join(2000);

        synchronized (sharedMemory) {
            sharedMemory.clear();
        }
        System.gc();

        System.out.println("ConcurrentTest completed");
        Runtime.getRuntime().halt(0);
    }
}

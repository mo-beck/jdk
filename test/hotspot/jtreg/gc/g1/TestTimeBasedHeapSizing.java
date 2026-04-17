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
 * @test TestTimeBasedHeapSizing
 * @bug 8357445
 * @summary Test time-based heap sizing functionality in G1
 * @requires vm.gc.G1
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management/sun.management
 * @run main/othervm -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions
 *     -Xms32m -Xmx128m -XX:G1HeapRegionSize=1M
 *     -XX:G1TimeBasedEvaluationIntervalMillis=5000
 *     -XX:G1UncommitDelayMillis=10000
 *     -XX:G1MinRegionsToUncommit=2
 *     -Xlog:gc*,gc+sizing*=debug
 *     gc.g1.TestTimeBasedHeapSizing
 */

import java.lang.management.ManagementFactory;
import java.util.*;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestTimeBasedHeapSizing {

    public static void main(String[] args) throws Exception {
        testBasicFunctionality();
        testHumongousObjectHandling();
        testRapidAllocationCycles();
        testLargeHumongousObjects();
    }

    static ProcessBuilder createSubprocess(String testClass, String... extraArgs) {
        List<String> args = new ArrayList<>();
        args.addAll(List.of(
            "-XX:+UseG1GC",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:G1TimeBasedEvaluationIntervalMillis=5000",
            "-XX:G1UncommitDelayMillis=10000",
            "-XX:G1MinRegionsToUncommit=2",
            "-XX:G1HeapRegionSize=1M",
            "-Xmx128m", "-Xms32m",
            "-Xlog:gc*,gc+sizing*=debug"
        ));
        args.addAll(List.of(extraArgs));
        args.add(testClass);
        return ProcessTools.createTestJavaProcessBuilder(args.toArray(new String[0]));
    }

    static long extractValue(String stdout, String prefix) {
        for (String line : stdout.split("\n")) {
            if (line.contains(prefix)) {
                return Long.parseLong(line.substring(line.indexOf(prefix) + prefix.length()).trim());
            }
        }
        throw new RuntimeException("Could not find '" + prefix + "' in subprocess output");
    }

    static void testBasicFunctionality() throws Exception {
        ProcessBuilder pb = createSubprocess("gc.g1.TestTimeBasedHeapSizing$BasicFunctionalityTest");
        OutputAnalyzer output = new OutputAnalyzer(pb.start());

        output.shouldContain("G1 Time-Based Heap Sizing enabled (uncommit-only)");
        output.shouldContain("Starting uncommit evaluation");
        output.shouldContain("Uncommit evaluation: found");

        // Verify committed memory actually decreased, not just that logs appeared.
        long before = extractValue(output.getStdout(), "HEAP_COMMITTED_BEFORE=");
        long after = extractValue(output.getStdout(), "HEAP_COMMITTED_AFTER=");
        if (after >= before) {
            throw new RuntimeException(
                "Expected committed heap to decrease after idle period, but before=" +
                before + " after=" + after);
        }
        System.out.println("Committed heap decreased from " + before + " to " + after);

        output.shouldHaveExitValue(0);
    }

    public static class BasicFunctionalityTest {
        private static final int MB = 1024 * 1024;
        private static ArrayList<byte[]> arrays = new ArrayList<>();

        public static void main(String[] args) throws Exception {
            for (int cycle = 0; cycle < 3; cycle++) {
                allocateMemory(25);
                Thread.sleep(200);
                arrays.clear();
                System.gc();
                Thread.sleep(200);
            }

            long before = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage().getCommitted();
            System.out.println("HEAP_COMMITTED_BEFORE=" + before);

            // Wait for evaluation interval (5s) + uncommit delay (10s) + margin.
            // Do not call System.gc() - it resets the GC timestamp baseline.
            Thread.sleep(18000);

            long after = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage().getCommitted();
            System.out.println("HEAP_COMMITTED_AFTER=" + after);

            Runtime.getRuntime().halt(0);
        }

        static void allocateMemory(int mb) throws InterruptedException {
            for (int i = 0; i < mb; i++) {
                arrays.add(new byte[MB]);
                if (i % 4 == 0) Thread.sleep(10);
            }
        }
    }

    static void testHumongousObjectHandling() throws Exception {
        ProcessBuilder pb = createSubprocess(
            "gc.g1.TestTimeBasedHeapSizing$HumongousObjectTest");
        OutputAnalyzer output = new OutputAnalyzer(pb.start());

        output.shouldContain("Starting uncommit evaluation");
        output.shouldHaveExitValue(0);
    }

    static void testRapidAllocationCycles() throws Exception {
        ProcessBuilder pb = createSubprocess(
            "gc.g1.TestTimeBasedHeapSizing$RapidCycleTest");
        OutputAnalyzer output = new OutputAnalyzer(pb.start());

        output.shouldContain("Starting uncommit evaluation");
        output.shouldHaveExitValue(0);
    }

    static void testLargeHumongousObjects() throws Exception {
        ProcessBuilder pb = createSubprocess(
            "gc.g1.TestTimeBasedHeapSizing$LargeHumongousTest",
            "-Xms64m", "-Xmx256m",
            "-XX:G1UncommitDelayMillis=5000",
            "-XX:G1MinRegionsToUncommit=1"
        );
        OutputAnalyzer output = new OutputAnalyzer(pb.start());

        output.shouldContain("G1 Time-Based Heap Sizing enabled (uncommit-only)");
        output.shouldHaveExitValue(0);
    }

    public static class HumongousObjectTest {
        private static ArrayList<byte[]> humongousObjects = new ArrayList<>();

        public static void main(String[] args) throws Exception {
            // Allocate humongous objects (> 512KB for 1MB regions).
            for (int i = 0; i < 8; i++) {
                humongousObjects.add(new byte[800 * 1024]);
                Thread.sleep(200);
            }

            Thread.sleep(3000);

            humongousObjects.clear();
            System.gc();
            // Wait for evaluation interval (5s) + uncommit delay (10s) + margin.
            Thread.sleep(18000);

            Runtime.getRuntime().halt(0);
        }
    }

    public static class RapidCycleTest {
        private static final int MB = 1024 * 1024;
        private static ArrayList<byte[]> memory = new ArrayList<>();

        public static void main(String[] args) throws Exception {
            for (int cycle = 0; cycle < 15; cycle++) {
                for (int i = 0; i < 8; i++) {
                    memory.add(new byte[MB]);
                }
                memory.clear();
                System.gc();
                Thread.sleep(100);
            }

            // Wait for evaluation interval (5s) + uncommit delay (10s) + margin.
            Thread.sleep(18000);

            Runtime.getRuntime().halt(0);
        }
    }

    public static class LargeHumongousTest {
        public static void main(String[] args) throws Exception {
            List<byte[]> humongousObjects = new ArrayList<>();

            // 2MB objects span multiple 1MB regions.
            for (int i = 0; i < 5; i++) {
                humongousObjects.add(new byte[2 * 1024 * 1024]);
                System.gc();
                Thread.sleep(100);
            }

            // Release some to create mixed region states.
            humongousObjects.remove(0);
            humongousObjects.remove(0);
            System.gc();

            // Wait for evaluation interval (5s) + uncommit delay (5s) + margin.
            Thread.sleep(14000);

            humongousObjects.clear();
            System.gc();

            Runtime.getRuntime().halt(0);
        }
    }
}

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
 * @test TestTimeBasedHeapConfig
 * @bug 8357445
 * @summary Test configuration settings and error conditions for time-based heap sizing
 * @requires vm.gc.G1
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management/sun.management
 * @run main/othervm -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions
 *     -Xms16m -Xmx64m -XX:G1HeapRegionSize=1M
 *     -XX:G1TimeBasedEvaluationIntervalMillis=5000
 *     -XX:G1UncommitDelayMillis=10000
 *     -XX:G1MinRegionsToUncommit=2
 *     -Xlog:gc*,gc+sizing*=debug
 *     gc.g1.TestTimeBasedHeapConfig
 */

import java.util.*;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestTimeBasedHeapConfig {

    public static void main(String[] args) throws Exception {
        testConfigurationParameters();
        testBoundaryValues();
        testEdgeCaseConfigurations();
    }

    static void testConfigurationParameters() throws Exception {
        verifyVMConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockDiagnosticVMOptions",
            "-Xms16m", "-Xmx64m",
            "-XX:G1HeapRegionSize=1M",
            "-Xlog:gc*,gc+sizing*=debug",
            "gc.g1.TestTimeBasedHeapConfig$BasicTest"
        });
    }

    private static void verifyVMConfig(String[] opts) throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(opts);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldHaveExitValue(0);
    }

    public static class BasicTest {
        private static final int MB = 1024 * 1024;
        private static ArrayList<byte[]> arrays = new ArrayList<>();

        public static void main(String[] args) throws Exception {
            allocateMemory(8);
            System.gc();
            Thread.sleep(1000);

            arrays.clear();
            System.gc();
            Thread.sleep(2000);

            Runtime.getRuntime().halt(0);
        }

        static void allocateMemory(int mb) throws InterruptedException {
            for (int i = 0; i < mb; i++) {
                arrays.add(new byte[MB]);
                if (i % 2 == 0) Thread.sleep(10);
            }
        }
    }

    static void testBoundaryValues() throws Exception {
        verifyVMConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockDiagnosticVMOptions",
            "-Xms8m", "-Xmx32m",
            "-XX:G1HeapRegionSize=1M",
            "-XX:G1TimeBasedEvaluationIntervalMillis=1000",
            "-XX:G1UncommitDelayMillis=1000",
            "-XX:G1MinRegionsToUncommit=1",
            "-Xlog:gc*,gc+sizing*=debug",
            "gc.g1.TestTimeBasedHeapConfig$BoundaryTest"
        });

        verifyVMConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockDiagnosticVMOptions",
            "-Xms32m", "-Xmx256m",
            "-XX:G1HeapRegionSize=1M",
            "-XX:G1TimeBasedEvaluationIntervalMillis=300000",
            "-XX:G1UncommitDelayMillis=300000",
            "-XX:G1MinRegionsToUncommit=50",
            "-Xlog:gc*,gc+sizing*=debug",
            "gc.g1.TestTimeBasedHeapConfig$BoundaryTest"
        });
    }

    static void testEdgeCaseConfigurations() throws Exception {
        verifyVMConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockDiagnosticVMOptions",
            "-Xms4m", "-Xmx8m",
            "-XX:G1HeapRegionSize=1M",
            "-XX:G1TimeBasedEvaluationIntervalMillis=2000",
            "-XX:G1UncommitDelayMillis=3000",
            "-XX:G1MinRegionsToUncommit=1",
            "-Xlog:gc*,gc+sizing*=debug",
            "gc.g1.TestTimeBasedHeapConfig$SmallHeapTest"
        });
    }

    public static class BoundaryTest {
        private static final int MB = 1024 * 1024;
        private static ArrayList<byte[]> arrays = new ArrayList<>();

        public static void main(String[] args) throws Exception {
            allocateMemory(4);
            Thread.sleep(2000);

            arrays.clear();
            System.gc();
            Thread.sleep(5000);

            Runtime.getRuntime().halt(0);
        }

        static void allocateMemory(int mb) throws InterruptedException {
            for (int i = 0; i < mb; i++) {
                arrays.add(new byte[MB]);
                Thread.sleep(10);
            }
        }
    }

    public static class SmallHeapTest {
        public static void main(String[] args) throws Exception {
            byte[] smallAlloc = new byte[1024 * 1024];
            Thread.sleep(2000);

            smallAlloc = null;
            System.gc();
            Thread.sleep(5000);

            Runtime.getRuntime().halt(0);
        }
    }
}

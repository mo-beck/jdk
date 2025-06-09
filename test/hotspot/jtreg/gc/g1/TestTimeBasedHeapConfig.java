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
 * @run main/othervm -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+G1UseTimeBasedHeapSizing -Xlog:gc*=debug,gc+sizing=debug gc.g1.TestTimeBasedHeapConfig
 */

import java.util.*;
import java.lang.management.ManagementFactory;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestTimeBasedHeapConfig {

    public static void main(String[] args) throws Exception {
        testConfigurationParameters();
        testInvalidSettings();
        testDynamicUpdates();
        testBoundaryConditions();
    }

    /**
     * Test various configuration parameter combinations
     */
    static void testConfigurationParameters() throws Exception {
        // Test default settings
        verifyVMConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+G1UseTimeBasedHeapSizing",
            "-Xlog:gc*=debug,gc+sizing=debug",
            "gc.g1.TestTimeBasedHeapConfig$DynamicUpdateTest"
        });

        // Test custom evaluation interval
        verifyVMConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+G1UseTimeBasedHeapSizing",
            "-XX:G1TimeBasedEvaluationIntervalMillis=30000",
            "-Xlog:gc*=debug,gc+sizing=debug",
            "gc.g1.TestTimeBasedHeapConfig$DynamicUpdateTest"
        });

        // Test custom uncommit delay
        verifyVMConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockExperimentalVMOptions", 
            "-XX:+G1UseTimeBasedHeapSizing",
            "-XX:G1UncommitDelayMillis=120000",
            "gc.g1.TestTimeBasedHeapConfig$DynamicUpdateTest"
        });

        // Test custom region threshold
        verifyVMConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+G1UseTimeBasedHeapSizing", 
            "-XX:G1MinRegionsToUncommit=5",
            "gc.g1.TestTimeBasedHeapConfig$DynamicUpdateTest"
        });
    }

    /**
     * Test invalid configuration settings
     */
    static void testInvalidSettings() throws Exception {
        // Test invalid evaluation interval
        verifyVMErrorConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+G1UseTimeBasedHeapSizing",
            "-XX:G1TimeBasedEvaluationIntervalMillis=0",  // Invalid
            "-Xlog:gc*=debug,gc+sizing=debug",
            "gc.g1.TestTimeBasedHeapConfig$DynamicUpdateTest"
        });

        // Test invalid uncommit delay
        verifyVMErrorConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+G1UseTimeBasedHeapSizing",
            "-XX:G1UncommitDelayMillis=100",  // Too low
            "-Xlog:gc*=debug,gc+sizing=debug",
            "gc.g1.TestTimeBasedHeapConfig$DynamicUpdateTest"
        });

        // Test invalid region count
        verifyVMErrorConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+G1UseTimeBasedHeapSizing",
            "-XX:G1MinRegionsToUncommit=0",  // Invalid
            "-Xlog:gc*=debug,gc+sizing=debug",
            "gc.g1.TestTimeBasedHeapConfig$DynamicUpdateTest"
        });
    }

    /**
     * Test dynamic parameter updates
     */
    static void testDynamicUpdates() throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
            "-XX:+UseG1GC",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+G1UseTimeBasedHeapSizing",
            "-Xlog:gc*=debug,gc+sizing=debug",
            "gc.g1.TestTimeBasedHeapConfig$DynamicUpdateTest");
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldHaveExitValue(0);
    }

    /**
     * Test boundary conditions
     */
    static void testBoundaryConditions() throws Exception {
        // Test minimum heap size
        verifyVMConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+G1UseTimeBasedHeapSizing",
            "-Xms5m",  // Very small initial heap
            "-Xmx128m",
            "-Xlog:gc*=debug,gc+sizing=debug",
            "gc.g1.TestTimeBasedHeapConfig$DynamicUpdateTest"
        });

        // Test maximum regions
        verifyVMConfig(new String[] {
            "-XX:+UseG1GC",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+G1UseTimeBasedHeapSizing",
            "-XX:G1MinRegionsToUncommit=100",  // Large region count
            "-Xmx2g",
            "-Xlog:gc*=debug,gc+sizing=debug",
            "gc.g1.TestTimeBasedHeapConfig$DynamicUpdateTest"
        });
    }

    private static void verifyVMConfig(String[] opts) throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(opts);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldHaveExitValue(0);
    }

    private static void verifyVMErrorConfig(String[] opts) throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(opts);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldNotHaveExitValue(0);
    }

    /**
     * Tests dynamic parameter updates
     */
    public static class DynamicUpdateTest {
        private static final int MB = 1024 * 1024;
        private static ArrayList<byte[]> arrays = new ArrayList<>();
        
        public static void main(String[] args) throws Exception {
            HotSpotDiagnosticMXBean diagnostic = ManagementFactory.getPlatformMXBean(
                HotSpotDiagnosticMXBean.class);

            // Initial allocation
            allocateMemory(100);
            System.gc();

            // Update parameters
            diagnostic.setVMOption("G1TimeBasedEvaluationIntervalMillis", "45000");
            diagnostic.setVMOption("G1UncommitDelayMillis", "90000");
            
            // More allocation 
            allocateMemory(50);
            System.gc();

            arrays = null;
            System.gc();
        }

        static void allocateMemory(int mb) {
            for (int i = 0; i < mb; i++) {
                arrays.add(new byte[MB]);
            }
        }
    }
}

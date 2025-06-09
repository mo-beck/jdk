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
 * @run main/othervm -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+G1UseTimeBasedHeapSizing gc.g1.TestTimeBasedRegionTracking 
 */

import java.util.*;
import java.lang.management.ManagementFactory;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestTimeBasedRegionTracking {

    // Test configuration  
    private static final String TEST_VM_OPTS = "-XX:+UseG1GC " +
        "-XX:+UnlockExperimentalVMOptions " +
        "-XX:+G1UseTimeBasedHeapSizing " +
        "-XX:G1TimeBasedEvaluationIntervalMillis=15000 " + // 15s for testing
        "-XX:G1UncommitDelayMillis=30000 " +  // 30s for testing
        "-XX:G1MinRegionsToUncommit=2 " +  // Low for testing
        "-Xmx1g -Xms512m " +  // Start with headroom
        "-Xlog:gc*=debug,gc+sizing=debug";

    public static void main(String[] args) throws Exception {
        testRegionStateTransitions();
        testConcurrentAllocation();
        testRegionReuse();
    }

    /**
     * Test region state transitions through allocation/collection cycles
     */
    static void testRegionStateTransitions() throws Exception {
        String[] command = new String[TEST_VM_OPTS.split(" ").length + 1];
        System.arraycopy(TEST_VM_OPTS.split(" "), 0, command, 0, TEST_VM_OPTS.split(" ").length);
        command[command.length - 1] = "gc.g1.TestTimeBasedRegionTracking$RegionTransitionTest";
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(command);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        
        // Verify region state changes
        output.shouldContain("Region state transition");
        output.shouldContain("Uncommit candidates found");
        
        output.shouldHaveExitValue(0);
    }

    /**
     * Test region tracking during concurrent allocations
     */
    static void testConcurrentAllocation() throws Exception {
        String[] command = new String[TEST_VM_OPTS.split(" ").length + 1];
        System.arraycopy(TEST_VM_OPTS.split(" "), 0, command, 0, TEST_VM_OPTS.split(" ").length);
        command[command.length - 1] = "gc.g1.TestTimeBasedRegionTracking$ConcurrentAllocationTest";
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(command);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        
        // Verify concurrent allocation tracking
        output.shouldContain("[gc,heap      ] GC(");  // GC log entry
        output.shouldContain("[gc,phases    ] Phase 1: Mark live objects");
        
        output.shouldHaveExitValue(0);
    }

    /**
     * Test proper tracking when regions are reused
     */
    static void testRegionReuse() throws Exception {
        String[] command = new String[TEST_VM_OPTS.split(" ").length + 1];
        System.arraycopy(TEST_VM_OPTS.split(" "), 0, command, 0, TEST_VM_OPTS.split(" ").length);
        command[command.length - 1] = "gc.g1.TestTimeBasedRegionTracking$RegionReuseTest";
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(command);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        
        // Verify region reuse tracking 
        output.shouldContain("[gc,heap      ] GC(");  // GC log entry
        output.shouldContain("[gc,phases    ] Phase 2: Compute new object locations");
        
        output.shouldHaveExitValue(0);
    }

    /**
     * Tests region state transitions 
     */
    public static class RegionTransitionTest {
        private static final int MB = 1024 * 1024;
        private static ArrayList<byte[]> arrays = new ArrayList<>();

        public static void main(String[] args) throws Exception {
            // Phase 1: Active allocation
            allocateMemory(200); // 200MB
            System.gc();
            
            // Phase 2: Idle period
            arrays.clear();
            System.gc();
            // Sleep for enough time to:
            // 1. Pass G1UncommitDelayMillis (30s)
            // 2. Ensure we hit the next evaluation interval (15s)
            Thread.sleep(45000); 
            
            // Phase 3: Reallocation
            allocateMemory(100); // 100MB
            System.gc();

            // Clean up
            arrays = null;
            System.gc();
        }
        
        static void allocateMemory(int mb) {
            for (int i = 0; i < mb; i++) {
                arrays.add(new byte[MB]);
            }
        }
    }

    /**
     * Tests concurrent allocation behavior
     */
    public static class ConcurrentAllocationTest {
        private static final int MB = 1024 * 1024;
        private static final int NUM_THREADS = 4;
        private static volatile boolean running = true;
        
        static class AllocationThread extends Thread {
            private final ArrayList<byte[]> arrays = new ArrayList<>();
            
            @Override
            public void run() {
                try {
                    while (running) {
                        // Allocate 10MB per iteration
                        for (int i = 0; i < 10 && running; i++) {
                            arrays.add(new byte[MB]);
                        }
                        Thread.sleep(100); // Short pause between allocations
                    }
                } catch (InterruptedException e) {
                    // Expected on shutdown
                }
            }
        }
        
        public static void main(String[] args) throws Exception {
            // Start concurrent allocation threads
            Thread[] threads = new Thread[NUM_THREADS];
            for (int i = 0; i < NUM_THREADS; i++) {
                threads[i] = new AllocationThread();
                threads[i].start();
            }
            
            // Let allocations run for a while
            Thread.sleep(10000);
            
            // Trigger GC to observe region state tracking
            System.gc();
            
            // Stop allocation threads
            running = false;
            for (Thread t : threads) {
                t.join();
            }
            
            // Final GC to clean up
            System.gc();
        }
    }

    /**
     * Tests region reuse behavior and tracking
     */
    public static class RegionReuseTest {
        private static final int MB = 1024 * 1024;
        private static ArrayList<byte[]> arrays = new ArrayList<>();
        
        static void allocateAndCollect(int mbToAllocate) throws Exception {
            // Allocate memory
            for (int i = 0; i < mbToAllocate; i++) {
                arrays.add(new byte[MB]);
            }
            
            // Force a GC to trigger region reuse
            System.gc();
            arrays.clear();
            
            // Let regions become eligible for uncommit and ensure evaluation occurs
            Thread.sleep(45000); // > G1UncommitDelayMillis + G1TimeBasedEvaluationIntervalMillis
        }
        
        public static void main(String[] args) throws Exception {
            // Phase 1: Initial allocation and collection
            allocateAndCollect(200); // 200MB
            
            // Phase 2: Reallocate in same regions
            allocateAndCollect(150); // 150MB
            
            // Phase 3: One more cycle with different size
            allocateAndCollect(100); // 100MB
            
            // Clean up
            arrays = null;
            System.gc();
        }
    }
}

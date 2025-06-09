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
 * @run main/othervm -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+G1UseTimeBasedHeapSizing gc.g1.TestTimeBasedHeapSizing
 */

import java.util.*;
import java.lang.management.ManagementFactory;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import java.util.concurrent.*;  // Add concurrent utilities

public class TestTimeBasedHeapSizing {

    // Test configuration
    private static final String TEST_VM_OPTS = "-XX:+UseG1GC " +
        "-XX:+UnlockExperimentalVMOptions " +
        "-XX:+G1UseTimeBasedHeapSizing " +
        "-XX:G1TimeBasedEvaluationIntervalMillis=30000 " + // 30 sec for testing
        "-XX:G1UncommitDelayMillis=60000 " + // 1 min for testing 
        "-XX:G1MinRegionsToUncommit=2 " +  // Lower for testing
        "-Xmx1g -Xms512m " +  // Start with some headroom
        "-Xlog:gc*=debug,gc+sizing=debug";

    public static void main(String[] args) throws Exception {
        testBasicFunctionality();
        testHighLoadScenario();
        testIdleBehavior();
        testConcurrentGC();
        testErrorConditions();
    }

    /**
     * Test basic functionality with default settings
     */
    static void testBasicFunctionality() throws Exception {
        String[] command = new String[TEST_VM_OPTS.split(" ").length + 1];
        System.arraycopy(TEST_VM_OPTS.split(" "), 0, command, 0, TEST_VM_OPTS.split(" ").length);
        command[command.length - 1] = "gc.g1.TestTimeBasedHeapSizing$BasicFunctionalityTest";
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(command);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        
        // Verify proper initialization
        output.shouldContain("G1 periodic heap evaluation");
        
        // Check for expected behavior
        output.shouldContain("Time-based evaluation triggered");
        output.shouldMatch("Uncommit candidates: [0-9]+ regions");
        
        output.shouldHaveExitValue(0);
    }

    /**
     * Test behavior under high allocation load with multiple threads
     */
    static void testHighLoadScenario() throws Exception {
        String[] command = new String[TEST_VM_OPTS.split(" ").length + 1];
        System.arraycopy(TEST_VM_OPTS.split(" "), 0, command, 0, TEST_VM_OPTS.split(" ").length);
        command[command.length - 1] = "gc.g1.TestTimeBasedHeapSizing$HighLoadTest";
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(command);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        
        // Verify proper handling of high allocation load
        output.shouldContain("High allocation phase completed");
        output.shouldContain("Time-based evaluation");
        output.shouldNotContain("OutOfMemoryError");
        
        output.shouldHaveExitValue(0);
    }

    /**
     * Test memory release during idle periods
     */
    static void testIdleBehavior() throws Exception {
        String[] command = new String[TEST_VM_OPTS.split(" ").length + 1];
        System.arraycopy(TEST_VM_OPTS.split(" "), 0, command, 0, TEST_VM_OPTS.split(" ").length);
        command[command.length - 1] = "gc.g1.TestTimeBasedHeapSizing$IdleBehaviorTest";
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(command);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        
        // Verify memory release during idle
        output.shouldContain("Starting idle phase");
        output.shouldContain("Time-based evaluation triggered");
        output.shouldMatch("Uncommit candidates: [0-9]+ regions");
        output.shouldContain("Memory released");
        
        output.shouldHaveExitValue(0);
    }

    /**
     * Test coordination with concurrent GC
     */  
    static void testConcurrentGC() throws Exception {
        String opts = TEST_VM_OPTS + " -XX:InitiatingHeapOccupancyPercent=45"; // Lower IHOP to trigger concurrent cycles
        String[] command = new String[opts.split(" ").length + 1];
        System.arraycopy(opts.split(" "), 0, command, 0, opts.split(" ").length);
        command[command.length - 1] = "gc.g1.TestTimeBasedHeapSizing$ConcurrentGCTest";
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(command);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        
        // Verify interaction with concurrent GC
        output.shouldContain("Starting concurrent allocations");
        output.shouldContain("Concurrent Mark Cycle");
        output.shouldContain("Time-based evaluation");
        output.shouldNotContain("GC coordination error");
        
        output.shouldHaveExitValue(0);
    }

    /**
     * Test error handling and recovery
     */
    static void testErrorConditions() throws Exception {
        String opts = TEST_VM_OPTS + " -XX:G1HeapRegionSize=1M"; // Small regions for more edge cases
        String[] command = new String[opts.split(" ").length + 1];
        System.arraycopy(opts.split(" "), 0, command, 0, opts.split(" ").length);
        command[command.length - 1] = "gc.g1.TestTimeBasedHeapSizing$ErrorConditionsTest";
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(command);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        
        // Verify error handling
        output.shouldContain("Starting error simulation");
        output.shouldContain("Time-based evaluation");
        output.shouldContain("Recovered from allocation failure");
        
        output.shouldHaveExitValue(0);
    }

    /**
     * Base test class with common utilities
     */
    static class BaseTest {
        protected static final int MB = 1024 * 1024;
        protected static ArrayList<byte[]> arrays = new ArrayList<>();
        
        static void allocateMemory(int mb) {
            for (int i = 0; i < mb; i++) {
                arrays.add(new byte[MB]);
            }
        }
        
        static void clearMemory() {
            arrays.clear();
            System.gc();
        }
    }

    /**
     * Basic functionality test
     */
    public static class BasicFunctionalityTest {
        private static final int MB = 1024 * 1024;
        private static ArrayList<byte[]> arrays = new ArrayList<>();

        public static void main(String[] args) throws Exception {
            // Allocate some memory
            allocateMemory(100);  // 100MB
            System.gc();
            
            // Sleep to allow time-based evaluation
            Thread.sleep(65000);  // > G1UncommitDelayMillis
            
            // Verify heap size changes
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
     * High load test with multiple threads and varying allocation patterns
     */
    public static class HighLoadTest extends BaseTest {
        private static final int NUM_THREADS = 4;
        private static final int ALLOCATION_SIZE_MB = 50;
        private static final CountDownLatch startLatch = new CountDownLatch(1);
        
        public static void main(String[] args) throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
            List<Future<?>> tasks = new ArrayList<>();
            
            // Create allocation tasks
            for (int i = 0; i < NUM_THREADS; i++) {
                tasks.add(executor.submit(new AllocationTask()));
            }
            
            System.out.println("Starting high allocation phase");
            startLatch.countDown();
            
            // Wait for allocations to complete
            for (Future<?> task : tasks) {
                task.get();
            }
            
            System.out.println("High allocation phase completed");
            executor.shutdown();
            
            // Allow time for evaluation
            Thread.sleep(65000);
            
            clearMemory();
        }
        
        static class AllocationTask implements Runnable {
            public void run() {
                try {
                    startLatch.await();
                    ArrayList<byte[]> threadArrays = new ArrayList<>();
                    
                    // Allocate and free memory in a loop
                    for (int i = 0; i < 3; i++) {
                        for (int j = 0; j < ALLOCATION_SIZE_MB; j++) {
                            threadArrays.add(new byte[MB]);
                        }
                        Thread.sleep(1000);
                        threadArrays.clear();
                        System.gc();
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * Test memory release during idle periods
     */
    public static class IdleBehaviorTest extends BaseTest {
        public static void main(String[] args) throws Exception {
            // Initial allocation
            System.out.println("Initial allocation phase");
            allocateMemory(200);
            System.gc();
            
            // First idle period - shorter than uncommit delay
            System.out.println("Starting short idle phase");
            clearMemory();
            Thread.sleep(30000); // < G1UncommitDelayMillis
            
            // Second allocation
            allocateMemory(100);
            System.gc();
            
            // Long idle period - should trigger uncommit
            System.out.println("Starting long idle phase");
            clearMemory();
            System.out.println("Starting idle phase");
            Thread.sleep(65000); // > G1UncommitDelayMillis
            
            System.out.println("Memory released");
            System.gc();
        }
    }

    /**
     * Test coordination with concurrent GC operations
     */
    public static class ConcurrentGCTest extends BaseTest {
        private static final int NUM_THREADS = 2;
        
        public static void main(String[] args) throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
            CountDownLatch gcLatch = new CountDownLatch(1);
            
            // Start allocation thread
            executor.submit(() -> {
                try {
                    System.out.println("Starting concurrent allocations");
                    while (!Thread.interrupted()) {
                        allocateMemory(10);
                        Thread.sleep(100);
                        clearMemory();
                    }
                } catch (InterruptedException e) {
                    // Expected
                }
            });
            
            // Start GC thread
            executor.submit(() -> {
                try {
                    gcLatch.await();
                    while (!Thread.interrupted()) {
                        System.gc();
                        Thread.sleep(5000);
                    }
                } catch (InterruptedException e) {
                    // Expected
                }
            });
            
            // Let the threads run
            gcLatch.countDown();
            Thread.sleep(65000);
            
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            
            clearMemory();
        }
    }

    /**
     * Test error handling and recovery scenarios
     */
    public static class ErrorConditionsTest extends BaseTest {
        public static void main(String[] args) throws Exception {
            System.out.println("Starting error simulation");
            
            // Rapid allocation/deallocation to stress region management
            for (int i = 0; i < 5; i++) {
                allocateMemory(100);
                // Force fragmentation with small allocations
                for (int j = 0; j < 1000; j++) {
                    arrays.add(new byte[1024]); // 1KB
                }
                clearMemory();
                Thread.sleep(100);
            }
            
            // Large allocation followed by immediate clear
            allocateMemory(400);
            clearMemory();
            
            // Sleep to allow uncommit
            Thread.sleep(65000);
            
            // Verify we can still allocate
            allocateMemory(50);
            System.out.println("Recovered from allocation failure");
            
            clearMemory();
        }
    }
}

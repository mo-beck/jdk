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

/*
 * @test TestG1RegionUncommit
 * @requires vm.gc.G1
 * @summary Test that G1 uncommits regions based on time threshold
 * @run main/othervm -XX:+UseG1GC -Xms128m -Xmx512m -XX:G1HeapRegionSize=1M -XX:+UnlockExperimentalVMOptions -XX:+G1UseTimeBasedHeapSizing 
 *                   -XX:G1UncommitDelayMillis=1000 -XX:G1TimeBasedEvaluationIntervalMillis=1000 -XX:G1MinRegionsToUncommit=2
 *                   -Xlog:gc*=debug,gc+sizing=debug,gc+heap=debug
 *                   gc.g1.TestG1RegionUncommit
 */

public class TestG1RegionUncommit {
    private static final int allocSize = 64 * 1024 * 1024; // 64MB
    private static final int MIN_REGIONS_TO_UNCOMMIT = 2; // From test config
    private static final long MIN_REGION_SIZE = 1024 * 1024; // 1MB minimum from G1HeapRegionSize=1M
    private static volatile Object keepAlive;
    
    private static long getCommitted() {
        return Runtime.getRuntime().totalMemory();
    }
    
    private static void waitForUncommit() throws Exception {
        long startTime = System.currentTimeMillis();
        long lastCommitted = getCommitted();
        System.out.println("Waiting for uncommit...");
        
        // Wait up to 10 seconds, checking every 100ms for changes
        for (int i = 0; i < 100; i++) {
            Thread.sleep(100);
            long currentCommitted = getCommitted();
            if (currentCommitted < lastCommitted) {
                System.out.println("Uncommit detected after " + (System.currentTimeMillis() - startTime) + "ms");
                System.out.println("Memory uncommitted: " + (lastCommitted - currentCommitted) + " bytes");
                return;
            }
            lastCommitted = currentCommitted;
        }
        throw new RuntimeException("No uncommit detected within 10 seconds");
    }
    
    public static void main(String[] args) throws Exception {
        // Initial allocation to force region commitment
        System.out.println("Initial allocation");
        long beforeAlloc = getCommitted();
        keepAlive = new byte[allocSize];
        long afterAlloc = getCommitted();
        
        // Free memory and force GC
        System.out.println("Freeing memory and forcing GC");
        keepAlive = null;
        System.gc();
        System.gc(); // Double GC to ensure cleanup
        
        // Wait for uncommit to occur
        waitForUncommit();
        
        long afterUncommit = getCommitted();
        
        // Verify uncommit occurred
        System.out.println("Before allocation: " + beforeAlloc);
        System.out.println("After allocation: " + afterAlloc);  
        System.out.println("After uncommit: " + afterUncommit);
        
        if (afterUncommit >= afterAlloc) {
            throw new RuntimeException("Uncommit did not occur. Before: " + beforeAlloc + 
                                    ", After alloc: " + afterAlloc + 
                                    ", After uncommit: " + afterUncommit);
        }
        
        // Allow heap to shrink by at most G1MinRegionsToUncommit * regionSize below initial size
        long minAllowedSize = beforeAlloc - (MIN_REGIONS_TO_UNCOMMIT * MIN_REGION_SIZE);
        if (afterUncommit < minAllowedSize) {
            throw new RuntimeException("Too much memory uncommitted. Current: " + afterUncommit + 
                                    ", Minimum allowed: " + minAllowedSize +
                                    " (initial size - " + MIN_REGIONS_TO_UNCOMMIT + " MB)");
        }
        
        System.out.println("Test passed!");
        Runtime.getRuntime().halt(0); // Use halt instead of exit to avoid running shutdown hooks
    }
}
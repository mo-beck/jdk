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
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @run main/othervm -XX:+UseG1GC -Xms128m -Xmx512m -XX:G1HeapRegionSize=1M -XX:+UnlockExperimentalVMOptions -XX:+G1UseTimeBasedHeapSizing 
 *                   -XX:G1UncommitDelayMillis=1000 -XX:G1MinRegionsToUncommit=2
 *                   -Xlog:gc*=debug,gc+sizing=debug,gc+heap=debug
 *                   gc.g1.TestG1RegionUncommit
 */

import java.util.ArrayList;
import jdk.test.lib.Utils;

public class TestG1RegionUncommit {
    private static final int allocSize = 64 * 1024 * 1024; // 64MB
    private static final int MIN_REGIONS_TO_UNCOMMIT = 2; // From test config
    private static final long MIN_REGION_SIZE = 1024 * 1024; // 1MB minimum from G1HeapRegionSize=1M
    private static volatile Object keepAlive;
    
    private static long getCommitted() {
        return Runtime.getRuntime().totalMemory();
    }
    
    public static void main(String[] args) throws Exception {
        // Initial allocation to force region commitment
        System.out.println("Initial allocation");
        long beforeAlloc = getCommitted();
        keepAlive = new byte[allocSize];
        long afterAlloc = getCommitted();
        
        // Free memory and wait for uncommit
        System.out.println("Freeing memory");
        keepAlive = null;
        System.gc();
        
        // Wait longer than uncommit delay
        Thread.sleep(2000);
        
        long afterUncommit = getCommitted();
        
        // Verify uncommit occurred
        System.out.println("Before allocation: " + beforeAlloc);
        System.out.println("After allocation: " + afterAlloc);  
        System.out.println("After uncommit: " + afterUncommit);
        
        if (afterUncommit >= afterAlloc) {
            throw new RuntimeException("Uncommit did not occur");
        }
        
        // Allow heap to shrink by at most G1MinRegionsToUncommit * regionSize below initial size
        long minAllowedSize = beforeAlloc - (MIN_REGIONS_TO_UNCOMMIT * MIN_REGION_SIZE);
        if (afterUncommit < minAllowedSize) {
            throw new RuntimeException("Too much memory uncommitted. Current: " + afterUncommit + 
                                    ", Minimum allowed: " + minAllowedSize +
                                    " (initial size - " + MIN_REGIONS_TO_UNCOMMIT + " MB)");
        }
        
        System.out.println("Test passed!");
    }
}

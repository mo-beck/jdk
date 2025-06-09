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
 *
 */

#include "gc/g1/g1CollectedHeap.hpp"
#include "gc/g1/g1HeapEvaluationTask.hpp"
#include "gc/g1/g1HeapSizingPolicy.hpp"
#include "utilities/globalDefinitions.hpp"
#include "logging/log.hpp"
#include "memory/resourceArea.hpp"

G1HeapEvaluationTask::G1HeapEvaluationTask(G1CollectedHeap* g1h, G1HeapSizingPolicy* heap_sizing_policy) :
  G1ServiceTask("G1 Heap Evaluation Task"),
  _g1h(g1h),
  _heap_sizing_policy(heap_sizing_policy) {
}

void G1HeapEvaluationTask::execute() {
  log_debug(gc, sizing)("Starting heap evaluation");

  if (!G1UseTimeBasedHeapSizing) {
    return;
  }

  ResourceMark rm; // Ensure temporary resources are released

  bool should_expand = false;
  size_t resize_amount = _heap_sizing_policy->evaluate_heap_resize(should_expand);
  
  if (resize_amount > 0) {
    if (should_expand) {
      log_debug(gc, sizing)("Expanding heap by %zu bytes", resize_amount);
      _g1h->expand(resize_amount, _g1h->workers());
    } else {
      log_debug(gc, sizing)("Shrinking heap by %zu bytes", resize_amount); 
      _g1h->request_heap_shrink(resize_amount);
    }
  }

  schedule(G1TimeBasedEvaluationIntervalMillis);
}


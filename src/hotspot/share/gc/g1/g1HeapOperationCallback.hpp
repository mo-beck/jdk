#ifndef SHARE_GC_G1_G1HEAPOPERATIONCALLBACK_HPP
#define SHARE_GC_G1_G1HEAPOPERATIONCALLBACK_HPP

#include "memory/allocation.hpp"

// Forward declarations
class G1CollectedHeap;

// Interface for requesting heap operations without direct dependency on VM operations
class G1HeapOperationCallback : public CHeapObj<mtGC> {
public:
  virtual void request_shrink(size_t bytes) = 0;
  virtual ~G1HeapOperationCallback() = default;
};

#endif // SHARE_GC_G1_G1HEAPOPERATIONCALLBACK_HPP    
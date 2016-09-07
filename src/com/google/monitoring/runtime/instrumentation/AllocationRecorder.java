/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.monitoring.runtime.instrumentation;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

/**
 * The logic for recording allocations, called from bytecode rewritten by
 * {@link AllocationInstrumenter}.
 *
 * @author jeremymanson@google.com (Jeremy Manson)
 * @author fischman@google.com (Ami Fischman)
 */
public class AllocationRecorder {
  static {
    // Sun's JVMs in 1.5.0_06 and 1.6.0{,_01} have a bug where calling
    // Instrumentation.getObjectSize() during JVM shutdown triggers a
    // JVM-crashing assert in JPLISAgent.c, so we make sure to not call it after
    // shutdown.  There can still be a race here, depending on the extent of the
    // JVM bug, but this seems to be good enough.
    // instrumentation is volatile to make sure the threads reading it (in
    // recordAllocation()) see the updated value; we could do more
    // synchronization but it's not clear that it'd be worth it, given the
    // ambiguity of the bug we're working around in the first place.
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        setInstrumentation(null);
      }
    });
  }

  // See the comment above the addShutdownHook in the static block above
  // for why this is volatile.
  private static volatile Instrumentation instrumentation = null;

  static Instrumentation getInstrumentation() {
    return instrumentation;
  }

  static void setInstrumentation(Instrumentation inst) {
    instrumentation = inst;
  }

  // List of packages that can add samplers.
  private static final List<String> classNames = new ArrayList<String>();

  static {
    classNames.add("com.google.monitoring.runtime.");
  }

  // Used for reentrancy checks
  private static final ThreadLocal<Boolean> recordingAllocation = new ThreadLocal<Boolean>();
  
  // Will only record array allocations of at least this size
  private static final int MIN_ARRAY_SIZE = 500;


  /**
   * Records the allocation.  This method is invoked on every allocation
   * performed by the system.
   *
   * @param count the count of how many instances are being
   *   allocated, if an array is being allocated.  If an array is not being
   *   allocated, then this value will be -1.
   * @param desc the descriptor of the class/primitive type
   *   being allocated.
   * @param newObj the new <code>Object</code> whose allocation is being
   *   recorded.
   */
  public static void recordAllocation(int count, String desc, Object newObj) {
	 if (count < MIN_ARRAY_SIZE) {
		 return;
	 }
	  
    if (recordingAllocation.get() == Boolean.TRUE) {
      return;
    } 
      
    
    recordingAllocation.set(Boolean.TRUE);

    // Copy value into local variable to prevent NPE that occurs when
    // instrumentation field is set to null by this class's shutdown hook
    // after another thread passed the null check but has yet to call
    // instrumentation.getObjectSize()
    Instrumentation instr = instrumentation;
    if (instr != null) {
    	System.out.println("Allocating array " + desc + " of " + count + " elements");
    }

    recordingAllocation.set(Boolean.FALSE);
  }

}

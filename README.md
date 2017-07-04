The Allocation Instrumenter is a Java agent written using the [java.lang.instrument][] API and
[ASM][]. Each array allocation in your Java program is instrumented with code to check the size of the array being allocated.  If the array is above a certain size, a stack trace is printed.

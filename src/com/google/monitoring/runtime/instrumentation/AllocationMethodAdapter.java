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

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A <code>MethodVisitor</code> that instruments all heap allocation bytecodes
 * to record the allocation being done for profiling.
 * Instruments bytecodes that allocate heap memory to call a recording hook.
 *
 * @author Ami Fischman
 */
class AllocationMethodAdapter extends MethodVisitor {
  /**
   * The signature string the recorder method must have.  The method must be
   * static, return void, and take as arguments:
   * <ol>
   * <li>an int count of how many instances are being allocated.  -1 means a
   * simple new to distinguish from a 1-element array.  0 shows up as a value
   * here sometimes; one reason is toArray()-type methods that require an array
   * type argument (see ArrayList.toArray() for example).</li>
   * <li>a String descriptor of the class/primitive type being allocated.</li>
   * <li>an Object reference to the just-allocated Object.</li>
   * </ol>
   */
  public static final String RECORDER_SIGNATURE =
      "(ILjava/lang/String;Ljava/lang/Object;)V";

  // A helper struct for describing the scope of temporary local variables we
  // create as part of the instrumentation.
  private static class VariableScope {
    public final int index;
    public final Label start;
    public final Label end;
    public final String desc;
    public VariableScope(int index, Label start, Label end, String desc) {
      this.index = index; this.start = start; this.end = end; this.desc = desc;
    }
  }

  // Dictionary of primitive type opcode to english name.
  private static final String[] primitiveTypeNames = new String[] {
    "INVALID0", "INVALID1", "INVALID2", "INVALID3",
    "boolean", "char", "float", "double",
    "byte", "short", "int", "long"
  };

  // To track the difference between <init>'s called as the result of a NEW
  // and <init>'s called because of superclass initialization, we track the
  // number of NEWs that still need to have their <init>'s called.
  private int outstandingAllocs = 0;

  // We need to set the scope of any local variables we materialize;
  // accumulate the scopes here and set them all at the end of the visit to
  // ensure all labels have been resolved.  Allocated on-demand.
  private List<VariableScope> localScopes = null;

  private  List<VariableScope> getLocalScopes() {
    if (localScopes == null) {
      localScopes = new LinkedList<VariableScope>();
    }
    return localScopes;
  }

  private final String recorderClass;
  private final String recorderMethod;

  /**
   * The LocalVariablesSorter used in this adapter.  Lame that it's public but
   * the ASM architecture requires setting it from the outside after this
   * AllocationMethodAdapter is fully constructed and the LocalVariablesSorter
   * constructor requires a reference to this adapter.  The only setter of
   * this should be AllocationClassAdapter.visitMethod().
   */
  public LocalVariablesSorter lvs = null;

  /**
   * A new AllocationMethodAdapter is created for each method that gets visited.
   */
  public AllocationMethodAdapter(MethodVisitor mv, String recorderClass,
                         String recorderMethod) {
    super(Opcodes.ASM5, mv);
    this.recorderClass = recorderClass;
    this.recorderMethod = recorderMethod;
  }

  /**
   * newarray shows up as an instruction taking an int operand (the primitive
   * element type of the array) so we hook it here.
   */
  @Override
  public void visitIntInsn(int opcode, int operand) {
    if (opcode == Opcodes.NEWARRAY) {
      // instack: ... count
      // outstack: ... aref
      if (operand >= 4 && operand <= 11) {
        super.visitInsn(Opcodes.DUP); // -> stack: ... count count
        super.visitIntInsn(opcode, operand); // -> stack: ... count aref
        invokeRecordAllocation(primitiveTypeNames[operand]);
        // -> stack: ... aref
      } else {
        AllocationInstrumenter.logger.severe("NEWARRAY called with an invalid operand " +
                      operand + ".  Not instrumenting this allocation!");
        super.visitIntInsn(opcode, operand);
      }
    } else {
      super.visitIntInsn(opcode, operand);
    }
  }

  // Helper method to compute class name as a String and push it on the stack.
  // pre: stack: ... class
  // post: stack: ... class className
  private void pushClassNameOnStack() {
    super.visitInsn(Opcodes.DUP);
    // -> stack: ... class class
    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
        "getName", "()Ljava/lang/String;", false);
    // -> stack: ... class classNameDotted
    super.visitLdcInsn('.');
    // -> stack: ... class classNameDotted '.'
    super.visitLdcInsn('/');
    // -> stack: ... class classNameDotted '.' '/'
    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
        "replace", "(CC)Ljava/lang/String;", false);
    // -> stack: ... class className
  }

  // Helper method to compute the product of an integer array and push it on
  // the stack.
  // pre: stack: ... intArray
  // post: stack: ... intArray product
  private void pushProductOfIntArrayOnStack() {
    Label beginScopeLabel = new Label();
    Label endScopeLabel = new Label();

    int dimsArrayIndex = newLocal("[I", beginScopeLabel, endScopeLabel);
    int counterIndex = newLocal("I", beginScopeLabel, endScopeLabel);
    int productIndex = newLocal("I", beginScopeLabel, endScopeLabel);
    Label loopLabel = new Label();
    Label endLabel = new Label();

    super.visitLabel(beginScopeLabel);

    // stack: ... intArray
    super.visitVarInsn(Opcodes.ASTORE, dimsArrayIndex);
    // -> stack: ...

    // counter = 0
    super.visitInsn(Opcodes.ICONST_0);
    super.visitVarInsn(Opcodes.ISTORE, counterIndex);
    // product = 1
    super.visitInsn(Opcodes.ICONST_1);
    super.visitVarInsn(Opcodes.ISTORE, productIndex);
    // loop:
    super.visitLabel(loopLabel);
    // if index >= arraylength goto end:
    super.visitVarInsn(Opcodes.ILOAD, counterIndex);
    super.visitVarInsn(Opcodes.ALOAD, dimsArrayIndex);
    super.visitInsn(Opcodes.ARRAYLENGTH);
    super.visitJumpInsn(Opcodes.IF_ICMPGE, endLabel);
    // product = product * max(array[counter],1)
    super.visitVarInsn(Opcodes.ALOAD, dimsArrayIndex);
    super.visitVarInsn(Opcodes.ILOAD, counterIndex);
    super.visitInsn(Opcodes.IALOAD);
    super.visitInsn(Opcodes.DUP);
    Label nonZeroDimension = new Label();
    super.visitJumpInsn(Opcodes.IFNE, nonZeroDimension);
    super.visitInsn(Opcodes.POP);
    super.visitInsn(Opcodes.ICONST_1);
    super.visitLabel(nonZeroDimension);
    super.visitVarInsn(Opcodes.ILOAD, productIndex);
    super.visitInsn(Opcodes.IMUL); // if overflow happens it happens.
    super.visitVarInsn(Opcodes.ISTORE, productIndex);
    // iinc counter 1
    super.visitIincInsn(counterIndex, 1);
    // goto loop
    super.visitJumpInsn(Opcodes.GOTO, loopLabel);
    // end:
    super.visitLabel(endLabel);
    // re-push dimensions array
    super.visitVarInsn(Opcodes.ALOAD, dimsArrayIndex);
    // push product
    super.visitVarInsn(Opcodes.ILOAD, productIndex);

    super.visitLabel(endScopeLabel);
  }

  /**
   * Reflection-based allocation (@see java.lang.reflect.Array#newInstance) is
   * triggered with a static method call (INVOKESTATIC), so we hook it here.
   * Class initialization is triggered with a constructor call (INVOKESPECIAL)
   * so we hook that here too as a proxy for the new bytecode which leaves an
   * uninitialized object on the stack that we're not allowed to touch.
   * {@link java.lang.Object#clone} is also a call to INVOKESPECIAL,
   * and is hooked here.  {@link java.lang.Class#newInstance} and
   * {@link java.lang.reflect.Constructor#newInstance} are both
   * INVOKEVIRTUAL calls, so they are hooked here, as well.
   */
  @Override
  public void visitMethodInsn(int opcode, String owner, String name,
      String signature, boolean itf) {
    if (opcode == Opcodes.INVOKESTATIC &&
        // Array does its own native allocation.  Grr.
        owner.equals("java/lang/reflect/Array") &&
        name.equals("newInstance")) {
      if (signature.equals("(Ljava/lang/Class;I)Ljava/lang/Object;")) {

        Label beginScopeLabel = new Label();
        Label endScopeLabel = new Label();
        super.visitLabel(beginScopeLabel);

        // stack: ... class count
        int countIndex = newLocal("I", beginScopeLabel, endScopeLabel);
        super.visitVarInsn(Opcodes.ISTORE, countIndex);
        // -> stack: ... class
        pushClassNameOnStack();
        // -> stack: ... class className
        int typeNameIndex =
          newLocal("Ljava/lang/String;", beginScopeLabel, endScopeLabel);
        super.visitVarInsn(Opcodes.ASTORE, typeNameIndex);
        // -> stack: ... class
        super.visitVarInsn(Opcodes.ILOAD, countIndex);
        // -> stack: ... class count
        super.visitMethodInsn(opcode, owner, name, signature, itf);
        // -> stack: ... newobj
        super.visitInsn(Opcodes.DUP);
        // -> stack: ... newobj newobj
        super.visitVarInsn(Opcodes.ILOAD, countIndex);
        // -> stack: ... newobj newobj count
        super.visitInsn(Opcodes.SWAP);
        // -> stack: ... newobj count newobj
        super.visitVarInsn(Opcodes.ALOAD, typeNameIndex);
        super.visitLabel(endScopeLabel);
        // -> stack: ... newobj count newobj className
        super.visitInsn(Opcodes.SWAP);
        // -> stack: ... newobj count className newobj
        super.visitMethodInsn(Opcodes.INVOKESTATIC, recorderClass,
            recorderMethod, RECORDER_SIGNATURE, false);
        // -> stack: ... newobj
        return;
      } else if (signature.equals("(Ljava/lang/Class;[I)Ljava/lang/Object;")){
        Label beginScopeLabel = new Label();
        Label endScopeLabel = new Label();
        super.visitLabel(beginScopeLabel);

        int dimsArrayIndex = newLocal("[I", beginScopeLabel, endScopeLabel);
        // stack: ... class dimsArray
        pushProductOfIntArrayOnStack();
        // -> stack: ... class dimsArray product
        int productIndex = newLocal("I", beginScopeLabel, endScopeLabel);
        super.visitVarInsn(Opcodes.ISTORE, productIndex);
        // -> stack: ... class dimsArray

        super.visitVarInsn(Opcodes.ASTORE, dimsArrayIndex);
        // -> stack: ... class
        pushClassNameOnStack();
        // -> stack: ... class className
        int typeNameIndex =
          newLocal("Ljava/lang/String;", beginScopeLabel, endScopeLabel);
        super.visitVarInsn(Opcodes.ASTORE, typeNameIndex);
        // -> stack: ... class
        super.visitVarInsn(Opcodes.ALOAD, dimsArrayIndex);
        // -> stack: ... class dimsArray
        super.visitMethodInsn(opcode, owner, name, signature, itf);
        // -> stack: ... newobj

        super.visitInsn(Opcodes.DUP);
        // -> stack: ... newobj newobj
        super.visitVarInsn(Opcodes.ILOAD, productIndex);
        // -> stack: ... newobj newobj product
        super.visitInsn(Opcodes.SWAP);
        // -> stack: ... newobj product newobj
        super.visitVarInsn(Opcodes.ALOAD, typeNameIndex);
        super.visitLabel(endScopeLabel);
        // -> stack: ... newobj product newobj className
        super.visitInsn(Opcodes.SWAP);
        // -> stack: ... newobj product className newobj
        super.visitMethodInsn(Opcodes.INVOKESTATIC, recorderClass,
            recorderMethod, RECORDER_SIGNATURE, false);
        // -> stack: ... newobj
        return;
      }
    }

    if (opcode == Opcodes.INVOKEVIRTUAL) {
      if ("clone".equals(name) && owner.startsWith("[")) {
        super.visitMethodInsn(opcode, owner, name, signature, itf);

        int i = 0;
        while (i < owner.length()) {
          if (owner.charAt(i) != '[') {
            break;
          }
          i++;
        }
        if (i > 1) {
          // -> stack: ... newobj
          super.visitTypeInsn(Opcodes.CHECKCAST, owner);
          // -> stack: ... arrayref
          calculateArrayLengthAndDispatch(owner.substring(i), i);
        } else {
          // -> stack: ... newobj
          super.visitInsn(Opcodes.DUP);
          // -> stack: ... newobj newobj
          super.visitTypeInsn(Opcodes.CHECKCAST, owner);
          // -> stack: ... newobj arrayref
          super.visitInsn(Opcodes.ARRAYLENGTH);
          // -> stack: ... newobj length
          super.visitInsn(Opcodes.SWAP);
          // -> stack: ... length newobj
          invokeRecordAllocation(owner.substring(i));
        }
        return;
      } else if ("newInstance".equals(name)) {
    	  // NOT HANDLING "Class.newInstance" call... (because we would need to compute the object size explicitly
      }
    }

    // !!! THIS BLOCK HANDLES clone and "new-opcode" allocations.  I.e., non-array allocations.
    // (Array clone operations are handled above.)
    // This is expensive because the size must be computed manually.  Hopefully the large
    // objects I'm looking for will be arrays.  (Such large, non-array objects could only be
    // defined via byte code manipulation - I assume.  Hopefully that is not what the problem is.
    if (opcode == Opcodes.INVOKESPECIAL) {
      if ("clone".equals(name) && "java/lang/Object".equals(owner)) {
    	// NOT HANDLING "clone" call... (because we would need to compute the object size explicitly
      } else if ("<init>".equals(name) && outstandingAllocs > 0) {
    	// NOT HANDLING "NEW" opcode ... (because we would need to compute the object size explicitly
      }
    }

    super.visitMethodInsn(opcode, owner, name, signature, itf);
  }

  /**
   * new and anewarray bytecodes take a String operand for the type of
   * the object or array element so we hook them here.  Note that new doesn't
   * actually result in any instrumentation here; we just do a bit of
   * book-keeping and do the instrumentation following the constructor call
   * (because we're not allowed to touch the object until it is initialized).
   */
  @Override
  public void visitTypeInsn(int opcode, String typeName) {
    if (opcode == Opcodes.NEW) {
      // We can't actually tag this object right after allocation because it
      // must be initialized with a ctor before we can touch it (Verifier
      // enforces this).  Instead, we just note it and tag following
      // initialization.
      super.visitTypeInsn(opcode, typeName);
      ++outstandingAllocs;
    } else if (opcode == Opcodes.ANEWARRAY) {
      super.visitInsn(Opcodes.DUP);
      super.visitTypeInsn(opcode, typeName);
      invokeRecordAllocation(typeName);
    } else {
      super.visitTypeInsn(opcode, typeName);
    }
  }

  /**
   * Called by the ASM framework once the class is done being visited to
   * compute stack & local variable count maximums.
   */
  @Override
  public void visitMaxs(int maxStack, int maxLocals) {
    if (localScopes != null) {
      for (VariableScope scope : localScopes) {
        super.visitLocalVariable("xxxxx$" + scope.index, scope.desc, null,
            scope.start, scope.end, scope.index);
      }
    }
    super.visitMaxs(maxStack, maxLocals);
  }

  // Helper method to allocate a new local variable and account for its scope.
  private int newLocal(Type type, String typeDesc,
      Label begin, Label end) {
    int newVar = lvs.newLocal(type);
    getLocalScopes().add(new VariableScope(newVar, begin, end, typeDesc));
    return newVar;
  }

  // Sometimes I happen to have a string descriptor and sometimes a type;
  // these alternate versions let me avoid recomputing whatever I already
  // know.
  private int newLocal(String typeDescriptor, Label begin, Label end) {
    return newLocal(Type.getType(typeDescriptor), typeDescriptor, begin, end);
  }

  private static final Pattern namePattern =
      Pattern.compile("^\\[*L([^;]+);$");

  // Helper method to actually invoke the recorder function for an allocation
  // event.
  // pre: stack: ... count newobj
  // post: stack: ... newobj
  private void invokeRecordAllocation(String typeName) {
    Matcher matcher = namePattern.matcher(typeName);
    if (matcher.find()) {
      typeName = matcher.group(1);
    }
    // stack: ... count newobj
    super.visitInsn(Opcodes.DUP_X1);
    // -> stack: ... newobj count newobj
    super.visitLdcInsn(typeName);
    // -> stack: ... newobj count newobj typename
    super.visitInsn(Opcodes.SWAP);
    // -> stack: ... newobj count typename newobj
    super.visitMethodInsn(Opcodes.INVOKESTATIC,
        recorderClass, recorderMethod, RECORDER_SIGNATURE, false);
    // -> stack: ... newobj
  }

  /**
   * multianewarray gets its very own visit method in the ASM framework, so we
   * hook it here.  This bytecode is different from most in that it consumes a
   * variable number of stack elements during execution.  The number of stack
   * elements consumed is specified by the dimCount operand.
   */
  @Override
  public void visitMultiANewArrayInsn(String typeName, int dimCount) {
    // stack: ... dim1 dim2 dim3 ... dimN
    super.visitMultiANewArrayInsn(typeName, dimCount);
    // -> stack: ... aref
    calculateArrayLengthAndDispatch(typeName, dimCount);
  }

  void calculateArrayLengthAndDispatch(String typeName, int dimCount) {
    // Since the dimensions of the array are not known at instrumentation
    // time, we take the created multi-dimensional array and peel off nesting
    // levels from the left.  For each nesting layer we probe the array length
    // and accumulate a partial product which we can then feed the recording
    // function.

    // below we note the partial product of dimensions 1 to X-1 as productToX
    // (so productTo1 == 1 == no dimensions yet).  We denote by aref0 the
    // array reference at the current nesting level (the containing aref's [0]
    // element).  If we hit a level whose arraylength is 0 or whose
    // reference is null, there's no point continuing, so we shortcut
    // out.

    // This approach works pretty well when you create a new array with the
    // newarray bytecodes.  You can also create a new array by cloning an
    // existing array; an existing multidimensional array might have had some
    // of its [0] elements nulled out.  We currently deal with this by bailing
    // out, but arguably we should do something more principled (like calculate
    // the size of the multidimensional array from scratch if you are using
    // clone()).
    // TODO(java-platform-team): Do something about modified multidimensional
    // arrays and clone().
    Label zeroDimension = new Label();
    super.visitInsn(Opcodes.DUP); // -> stack: ... origaref aref0
    super.visitLdcInsn(1); // -> stack: ... origaref aref0 productTo1
    for (int i = 0; i < dimCount; ++i) {
      // pre: stack: ... origaref aref0 productToI
      super.visitInsn(Opcodes.SWAP); // -> stack: ... origaref productToI aref
      super.visitInsn(Opcodes.DUP);

      Label nonNullDimension = new Label();
      // -> stack: ... origaref productToI aref aref
      super.visitJumpInsn(Opcodes.IFNONNULL, nonNullDimension);
      // -> stack: ... origaref productToI aref
      super.visitInsn(Opcodes.SWAP);
      // -> stack: ... origaref aref productToI
      super.visitJumpInsn(Opcodes.GOTO, zeroDimension);
      super.visitLabel(nonNullDimension);

      // -> stack: ... origaref productToI aref
      super.visitInsn(Opcodes.DUP_X1);
      // -> stack: ... origaref aref0 productToI aref
      super.visitInsn(Opcodes.ARRAYLENGTH);
      // -> stack: ... origaref aref0 productToI dimI

      Label nonZeroDimension = new Label();
      super.visitInsn(Opcodes.DUP);
      // -> stack: ... origaref aref0 productToI dimI dimI
      super.visitJumpInsn(Opcodes.IFNE, nonZeroDimension);
      // -> stack: ... origaref aref0 productToI dimI
      super.visitInsn(Opcodes.POP);
      // -> stack: ... origaref aref0 productToI
      super.visitJumpInsn(Opcodes.GOTO, zeroDimension);
      super.visitLabel(nonZeroDimension);
      // -> stack: ... origaref aref0 productToI max(dimI,1)

      super.visitInsn(Opcodes.IMUL);
      // -> stack: ... origaref aref0 productTo{I+1}
      if (i < dimCount - 1) {
        super.visitInsn(Opcodes.SWAP);
        // -> stack: ... origaref productTo{I+1} aref0
        super.visitInsn(Opcodes.ICONST_0);
        // -> stack: ... origaref productTo{I+1} aref0 0
        super.visitInsn(Opcodes.AALOAD);
        // -> stack: ... origaref productTo{I+1} aref0'
        super.visitInsn(Opcodes.SWAP);
      }
      // post: stack: ... origaref aref0 productTo{I+1}
    }
    super.visitLabel(zeroDimension);

    super.visitInsn(Opcodes.SWAP); // -> stack: ... origaref product aref0
    super.visitInsn(Opcodes.POP); // -> stack: ... origaref product
    super.visitInsn(Opcodes.SWAP); // -> stack: ... product origaref
    invokeRecordAllocation(typeName);
  }
}

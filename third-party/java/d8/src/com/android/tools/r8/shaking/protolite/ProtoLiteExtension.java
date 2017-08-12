// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking.protolite;

import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DelegatingUseRegistry;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.shaking.Enqueuer.SemanticsProvider;
import com.android.tools.r8.utils.MethodJavaSignatureEquivalence;
import com.google.common.base.Equivalence;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Extends the tree shaker with special treatment for ProtoLite generated classes.
 * <p>
 * The goal is to remove unused fields from proto classes. To achieve this, we filter the
 * dependency processing of certain methods of ProtoLite classes. Read/write references to
 * fields or corresponding getters are ignored. If the fields or getters are not used otherwise
 * in the program, they will be pruned even though they are referenced from the processed
 * ProtoLite class.
 * <p>
 * The companion code rewriter in {@link ProtoLitePruner} then fixes up the code and removes
 * references to these dead fields and getters in a semantics preserving way. For proto2, the
 * fields are turned into unknown fields and hence are preserved over the wire. For proto3, the
 * fields are removed and, as with unknown fields in proto3, their data is lost on retransmission.
 * <p>
 * We have to process the following three methods specially:
 * <dl>
 *   <dt>dynamicMethod</dt>
 *   <dd>implements most proto operations, like merging, comparing, etc</dd>
 *   <dt>writeTo</dt>
 *   <dd>performs the actual write operations</dd>
 *   <dt>getSerializedSize</dt>
 *   <dd>implements computing the serialized size of a proto, very similar to writeTo</dd>
 * </dl>
 * As they access all fields of a proto, regardless of whether they are used, we have to mask
 * their accesses to ensure actually dead fields are not made live.
 * <p>
 * We also have to treat setters specially. While their code does not need to be rewritten, we
 * need to ensure that the fields they write are actually kept and marked as live. We achieve
 * this by also marking them read.
 */
public class ProtoLiteExtension extends ProtoLiteBase implements SemanticsProvider {

  private final Equivalence<DexMethod> equivalence = MethodJavaSignatureEquivalence.get();
  /**
   * Set of all methods directly defined on the GeneratedMessageLite class. Used to filter
   * getters from other methods that begin with get.
   */
  private final Set<Wrapper<DexMethod>> methodsOnMessageType;

  private final DexString caseGetterSuffix;
  private final DexString caseFieldSuffix;

  public ProtoLiteExtension(AppInfoWithSubtyping appInfo) {
    super(appInfo);
    DexItemFactory factory = appInfo.dexItemFactory;
    this.methodsOnMessageType = computeMethodsOnMessageType();
    this.caseGetterSuffix = factory.createString("Case");
    this.caseFieldSuffix = factory.createString("Case_");
  }

  private Set<Wrapper<DexMethod>> computeMethodsOnMessageType() {
    DexClass messageClass = appInfo.definitionFor(messageType);
    if (messageClass == null) {
      return Collections.emptySet();
    }
    Set<Wrapper<DexMethod>> superMethods = new HashSet<>();
    messageClass.forEachMethod(method -> superMethods.add(equivalence.wrap(method.method)));
    return superMethods;
  }

  @Override
  boolean isSetterThatNeedsProcessing(DexEncodedMethod method) {
    return method.accessFlags.isPrivate()
        && method.method.name.beginsWith(setterNamePrefix)
        && !methodsOnMessageType.contains(equivalence.wrap(method.method));
  }

  private boolean isGetter(DexMethod method, DexType instanceType) {
    return method.holder == instanceType
        && (method.proto.parameters.isEmpty() || hasSingleIntArgument(method))
        && method.name.beginsWith(getterNamePrefix)
        && !method.name.endsWith(caseGetterSuffix)
        && !methodsOnMessageType.contains(equivalence.wrap(method));
  }

  private boolean isProtoField(DexField field, DexType instanceType) {
    if (field.getHolder() != instanceType) {
      return false;
    }
    // All instance fields that end with _ are proto fields. For proto2, there are also the
    // bitField<n>_ fields that are used to store presence information. We process those normally.
    // Likewise, the XXXCase_ fields for oneOfs.
    DexString name = field.name;
    return name.endsWith(underscore)
        && !name.beginsWith(bitFieldPrefix)
        && !name.endsWith(caseFieldSuffix);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object processMethod(DexEncodedMethod method, UseRegistry registry, Object state) {
    return processMethod(method, registry, (Set<DexField>) state);
  }

  private Set<DexField> processMethod(DexEncodedMethod method, UseRegistry registry,
      Set<DexField> state) {
    if (state == null) {
      state = Sets.newIdentityHashSet();
    }
    if (isSetterThatNeedsProcessing(method)) {
      // If a field is accessed by a live setter, the field is live as it has to be written to the
      // serialized stream for this proto. As we mask all reads in the writing code and normally
      // remove fields that are only written but never read, we have to mark fields used in setters
      // as read and written.
      method.registerReachableDefinitions(
          new FieldWriteImpliesReadUseRegistry(registry, method.method.holder));
    } else {
      // Filter all getters and field accesses in these methods. We do not want fields to become
      // live just due to being referenced in a special method. The pruning phase will remove
      // all references to dead fields in the code later.
      method.registerReachableDefinitions(new FilteringUseRegistry(registry, method.method.holder,
          state));
    }
    return state;
  }

  private class FieldWriteImpliesReadUseRegistry extends DelegatingUseRegistry {

    private final DexType instanceType;

    FieldWriteImpliesReadUseRegistry(UseRegistry delegate,
        DexType instanceType) {
      super(delegate);
      this.instanceType = instanceType;
    }

    @Override
    public boolean registerInstanceFieldWrite(DexField field) {
      if (isProtoField(field, instanceType)) {
        super.registerInstanceFieldRead(field);
      }
      return super.registerInstanceFieldWrite(field);
    }
  }

  private class FilteringUseRegistry extends DelegatingUseRegistry {

    private final DexType instanceType;
    private final Set<DexField> registerField;

    private FilteringUseRegistry(UseRegistry delegate,
        DexType instanceType, Set<DexField> registerField) {
      super(delegate);
      this.instanceType = instanceType;
      this.registerField = registerField;
    }

    @Override
    public boolean registerInstanceFieldWrite(DexField field) {
      if (isProtoField(field, instanceType)) {
        registerField.add(field);
        return false;
      }
      return super.registerInstanceFieldWrite(field);
    }

    @Override
    public boolean registerInstanceFieldRead(DexField field) {
      if (isProtoField(field, instanceType)) {
        registerField.add(field);
        return false;
      }
      return super.registerInstanceFieldRead(field);
    }

    @Override
    public boolean registerInvokeVirtual(DexMethod method) {
      if (isGetter(method, instanceType)) {
        // Try whether this is a getXXX method.
        DexField field = getterToField(method);
        if (isProtoField(field, instanceType)) {
          registerField.add(field);
          return false;
        }
        // Try whether this is a getXXXCount method.
        field = getterToField(method, COUNT_POSTFIX_LENGTH);
        if (isProtoField(field, instanceType)) {
          registerField.add(field);
          return false;
        }
      }
      return super.registerInvokeVirtual(method);
    }
  }
}

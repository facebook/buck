/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: InstrVisitor.java,v 1.1.1.1.2.4 2004/07/16 23:32:28 vlad_r Exp $
 */
package com.vladium.emma.instr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import com.vladium.jcd.cls.*;
import com.vladium.jcd.cls.attribute.*;
import com.vladium.jcd.cls.constant.CONSTANT_Class_info;
import com.vladium.jcd.cls.constant.CONSTANT_Long_info;
import com.vladium.jcd.cls.constant.CONSTANT_Methodref_info;
import com.vladium.jcd.cls.constant.CONSTANT_String_info;
import com.vladium.jcd.compiler.CodeGen;
import com.vladium.jcd.lib.Types;
import com.vladium.jcd.opcodes.IOpcodes;
import com.vladium.logging.Logger;
import com.vladium.util.ByteArrayOStream;
import com.vladium.util.IConstants;
import com.vladium.util.IntIntMap;
import com.vladium.util.IntObjectMap;
import com.vladium.util.IntSet;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.data.ClassDescriptor;
import com.vladium.emma.data.CoverageOptions;
import com.vladium.emma.data.IMetadataConstants;
import com.vladium.emma.data.MethodDescriptor;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class InstrVisitor extends AbstractClassDefVisitor
                         implements IClassDefVisitor, IAttributeVisitor, IOpcodes, IConstants
{
    // public: ................................................................
    
    // TODO: m_instrument is unused

    public static final class InstrResult
    {
        public boolean m_instrumented;
        public ClassDescriptor m_descriptor;
        
    } // end of nested class    
    
    public InstrVisitor (final CoverageOptions options)
    {
        m_excludeSyntheticMethods = options.excludeSyntheticMethods ();
        m_excludeBridgeMethods = options.excludeBridgeMethods ();
        m_doSUIDCompensation = options.doSUIDCompensation ();
        
        m_log = Logger.getLogger ();
    }
    
    /**
     * Analyzes 'cls' and/or instruments it for coverage:
     * <ul>
     *  <li> if 'instrument' is true, the class definition is instrumented for
     *       coverage if that is feasible
     *  <li> if 'metadata' is true, the class definition is analysed
     *       to create a {@link ClassDescriptor} for the original class definition 
     * </ul>
     * This method returns null if 'metadata' is 'false' *or* if 'cls' is an
     * interface [the latter precludes coverage of interface static
     * initializers and may be removed in the future].<P>
     * 
     * NOTE: if 'instrument' is 'true', the caller should always assume that 'cls'
     * has been mutated by this method even if it returned null. The caller should
     * then revert to the original class definition that was created as a
     * <code>cls.clone()</code> or by retaining the original definition bytes.
     * This part of contract is for efficienty and also simplifies the implementation. 
     */
    public void process (final ClassDef cls,
                         final boolean ignoreAlreadyInstrumented,
                         final boolean instrument, final boolean metadata,
                         final InstrResult out)
    {
        out.m_instrumented = false;
        out.m_descriptor = null;
        
        if (! (instrument || metadata)) return; // nothing to do

        if (cls.isInterface ())
            return; // skip interfaces [may change in the future]
        else
        {
            reset ();
            
            m_cls = cls;
            
            // TODO: handle classes that cannot be instrumented due to bytecode/JVM limitations
            m_instrument = instrument;
            m_metadata = metadata;
            m_ignoreAlreadyInstrumented = ignoreAlreadyInstrumented;
            
            // TODO: create 'no instrumentation' execution path here
            
            visit ((ClassDef) null, null); // potentially changes m_instrument and m_metadata
            
            if (m_metadata)
            {
                setClassName (cls.getName ());
                
                out.m_descriptor = new ClassDescriptor (m_classPackageName, m_className, m_classSignature, m_classSrcFileName, m_classMethodDescriptors);
            }
            
            out.m_instrumented = m_instrument;
        }
    }
    

    // IClassDefVisitor:
    
    public Object visit (final ClassDef ignore, final Object ctx)
    {
        final ClassDef cls = m_cls;
        final String clsVMName = cls.getName ();
        final String clsName = Types.vmNameToJavaName (clsVMName);
        
        final boolean trace1 = m_log.atTRACE1 (); 
        if (trace1) m_log.trace1 ("visit", "class: [" + clsVMName + "]");
        
        
        // skip synthetic classes if enabled:
        if (SKIP_SYNTHETIC_CLASSES && cls.isSynthetic ())
        {
            m_instrument = false;
            m_metadata = false;
            
            if (trace1) m_log.trace1 ("visit", "skipping synthetic class");
            return ctx;
        }
        
        // TODO: ideally, this check should be done in outer scope somewhere
        if (! m_warningIssued && clsName.startsWith (IAppConstants.APP_PACKAGE))
        {
            m_warningIssued = true;
            
            m_log.warning (IAppConstants.APP_NAME + " classes appear to be included on the instrumentation");
            m_log.warning ("path: this is not a correct way to use " + IAppConstants.APP_NAME);
        }
        
        // field uniqueness check done to detect double instrumentation:
        {
            final int [] existing = cls.getFields (COVERAGE_FIELD_NAME);
            if (existing.length > 0)
            {
                m_instrument = false;
                m_metadata = false;
                
                if (m_ignoreAlreadyInstrumented)
                {
                    if (trace1) m_log.trace1 ("visit", "skipping instrumented class");
                    return ctx;
                }
                else
                {
                    // TODO: use a app coded exception
                    throw new IllegalStateException ("class [" + clsName + "] appears to be instrumented already");
                }
            }
        }
        
        final IConstantCollection constants = cls.getConstants ();
        
        SyntheticAttribute_info syntheticMarker = null;
        
        // cache the location of "Synthetic" string:
        {
            if (MARK_ADDED_ELEMENTS_SYNTHETIC)
                m_syntheticStringIndex = cls.addCONSTANT_Utf8 (Attribute_info.ATTRIBUTE_SYNTHETIC, true);
        }
                
        // add a Fieldref for the runtime coverage collector field:
        {
            // note: this is a bit premature if the class has no methods that need
            // instrumentation
            // TODO: the mutated version is easily discardable; however, this case
            // needs attention at metadata/report generation level
            
            final int coverageFieldOffset;
            final String fieldDescriptor = "[[Z";
            
            // note that post-4019 builds can modify this field outside of <clinit> (although
            // it can only happen as part of initializing a set of classes); however, it is legal
            // to declare this field final:
            
            final int fieldModifiers = IAccessFlags.ACC_PRIVATE | IAccessFlags.ACC_STATIC | IAccessFlags.ACC_FINAL;
            
            // add declared field:
            if (MARK_ADDED_ELEMENTS_SYNTHETIC)
            {
                final IAttributeCollection fieldAttributes = ElementFactory.newAttributeCollection (1);

                syntheticMarker = new SyntheticAttribute_info (m_syntheticStringIndex);
                fieldAttributes.add (syntheticMarker);
    
                coverageFieldOffset = cls.addField (COVERAGE_FIELD_NAME, fieldDescriptor,
                    fieldModifiers, fieldAttributes);
            }
            else
            {
                coverageFieldOffset = cls.addField (COVERAGE_FIELD_NAME, fieldDescriptor,
                    fieldModifiers);
            }
            
            //add fieldref:
            m_coverageFieldrefIndex = cls.addFieldref (coverageFieldOffset);
        }
        
        // add a Methodref for Runtime.r():
        {
            // TODO: compute this without loading Runtime Class?
            final String classJVMName = "com/vladium/emma/rt/RT";
            final int class_index = cls.addClassref (classJVMName);
            
            // NOTE: keep this descriptor in sync with the actual signature
            final String methodDescriptor = "([[ZLjava/lang/String;J)V";
            final int nametype_index = cls.addNameType ("r", methodDescriptor);
            
            m_registerMethodrefIndex = constants.add (new CONSTANT_Methodref_info (class_index, nametype_index));
        }
        
        // SF FR 971186: split the init logic into a separate method so it could
        // be called from regular method headers if necessary: 
        
        // add a Methodref for pre-<clinit> method:
        {
            // NOTE: keep this descriptor in sync with the actual signature
            final String methodDescriptor = "()[[Z";
            final int nametype_index = cls.addNameType (PRECLINIT_METHOD_NAME, methodDescriptor);
            
            m_preclinitMethodrefIndex = constants.add (new CONSTANT_Methodref_info (cls.getThisClassIndex (), nametype_index));
        }
        
        // add a CONSTANT_String that corresponds to the class name [in JVM format]:
        {
            m_classNameConstantIndex = constants.add (new CONSTANT_String_info (cls.getThisClass ().m_name_index));
        }
        
        // visit method collection:         
        visit (cls.getMethods (), ctx);
        
        // if necessary, do SUID compensation [need to be done after method
        // visits when it is known whether a <clinit> was added]:
        if (m_doSUIDCompensation)
        {
            // compensation not necessary if the original clsdef already defined <clinit>:
            boolean compensate = ((m_clinitStatus & IMetadataConstants.METHOD_ADDED) != 0);
            
            int existingSUIDFieldCount = 0;
            
            if (compensate)
            {
                // compensation not necessary if the original clsdef already controlled it via 'serialVersionUID':
                {
                    final int [] existing = cls.getFields (SUID_FIELD_NAME);
                    existingSUIDFieldCount = existing.length;
                    
                    if (existingSUIDFieldCount > 0)
                    {
                        final IFieldCollection fields = cls.getFields ();
                        
                        for (int f = 0; f < existingSUIDFieldCount; ++ f)
                        {
                            final Field_info field = fields.get (existing [f]);
                            if ((field.getAccessFlags () & (IAccessFlags.ACC_STATIC | IAccessFlags.ACC_FINAL))
                                 == (IAccessFlags.ACC_STATIC | IAccessFlags.ACC_FINAL))
                            {
                                // TODO: should also check for presence of a non-zero initializer
                                
                                compensate = false;
                                break;
                            }
                        }
                    }
                }
                
                // compensation not necessary if we can determine that this class
                // does not implement java.io.Serializable/Externalizable:
                
                if (compensate && (cls.getThisClassIndex () == 0)) // no superclasses [this tool can't traverse inheritance chains]
                {
                    boolean serializable = false;
                    
                    final IInterfaceCollection interfaces = cls.getInterfaces ();
                    for (int i = 0, iLimit = interfaces.size (); i < iLimit; ++ i)
                    {
                        final CONSTANT_Class_info ifc = (CONSTANT_Class_info) constants.get (interfaces.get (i));
                        final String ifcName = ifc.getName (cls); 
                        if (JAVA_IO_SERIALIZABLE_NAME.equals (ifcName) || JAVA_IO_EXTERNALIZABLE_NAME.equals (ifcName))
                        {
                            serializable = true;
                            break;
                        }
                    }
                    
                    if (! serializable) compensate = false;
                }
            }
            
            if (compensate)
            {
                if (existingSUIDFieldCount > 0)
                {
                    // if we get here, the class declares a 'serialVersionUID' field
                    // that is not both static and final and/or is not initialized
                    // statically: warn that SUID compensation may not work
                    
                    m_log.warning ("class [" + clsName + "] declares a 'serialVersionUID'");
                    m_log.warning ("field that is not static and final: this is likely an implementation mistake");
                    m_log.warning ("and can interfere with " + IAppConstants.APP_NAME + "'s SUID compensation");
                }
                
                final String fieldDescriptor = "J";
                final int fieldModifiers = IAccessFlags.ACC_PRIVATE | IAccessFlags.ACC_STATIC | IAccessFlags.ACC_FINAL;
                final IAttributeCollection fieldAttributes = ElementFactory.newAttributeCollection (MARK_ADDED_ELEMENTS_SYNTHETIC ? 2 : 1);
    
                final int nameIndex = cls.addCONSTANT_Utf8 (Attribute_info.ATTRIBUTE_CONSTANT_VALUE, true);
                final int valueIndex = constants.add (new CONSTANT_Long_info (cls.computeSUID (true))); // ignore the added <clinit>
                
                final ConstantValueAttribute_info initializer = new ConstantValueAttribute_info (nameIndex, valueIndex);
                fieldAttributes.add (initializer);
                    
                if (MARK_ADDED_ELEMENTS_SYNTHETIC)
                {
                    if (syntheticMarker == null) syntheticMarker = new SyntheticAttribute_info (m_syntheticStringIndex);
                    fieldAttributes.add (syntheticMarker);
                }
                
                cls.addField (SUID_FIELD_NAME, fieldDescriptor, fieldModifiers, fieldAttributes);
            }
            
        } // if (m_doSUIDCompensation)
        
        // visit class attributes [to get src file name, etc]:
        visit (cls.getAttributes (), ctx);
        
        return ctx;
    }

    
    public Object visit (final IMethodCollection methods, final Object ctx)
    {
        final ClassDef cls = m_cls;
        
        final boolean trace2 = m_log.atTRACE2 ();
        
        final int originalMethodCount = methods.size ();
        final boolean constructMetadata = m_metadata;
        
        // create block count map: TODO: is the extra slot really needed?
        // - create [potentially unused] slot for added <clinit>
        m_classBlockCounts = new int [originalMethodCount + 1];        
        
        if (constructMetadata)
        {
            // prepare to collect metadata:
            m_classBlockMetadata = new int [originalMethodCount + 1] [] []; // same comments as above
            
            m_classMethodDescriptors = new MethodDescriptor [originalMethodCount];
        }
        
       
        // visit each original method:
        
        for (int m = 0; m < originalMethodCount; ++ m)
        {
            final Method_info method = methods.get (m);
            m_methodName = method.getName (cls); 
            if (trace2) m_log.trace2 ("visit", (method.isSynthetic () ? "synthetic " : "") + "method #" + m + ": [" + m_methodName + "]");
            
            final boolean isClinit = IClassDefConstants.CLINIT_NAME.equals (m_methodName);
                        
            // TODO: research whether synthetic methods add nontrivially to line coverage or not
            
            boolean excluded = false;
            
            if (! isClinit)
            {
                if (m_excludeSyntheticMethods && method.isSynthetic ())
                {
                    excluded = true;
                    if (trace2) m_log.trace2 ("visit", "skipped synthetic method");
                }
                else if (m_excludeBridgeMethods && method.isBridge ())
                {
                    excluded = true;
                    if (trace2) m_log.trace2 ("visit", "skipped bridge method");
                } 
            }
            
            if (excluded)
            {
                if (constructMetadata)
                {
                    m_classMethodDescriptors [m] = new MethodDescriptor (m_methodName, method.getDescriptor (cls), IMetadataConstants.METHOD_EXCLUDED, m_methodBlockSizes, null, 0);
                }
            }
            else
            {
                if ((method.getAccessFlags () & (IAccessFlags.ACC_ABSTRACT | IAccessFlags.ACC_NATIVE)) != 0)
                {
                    if (constructMetadata)
                    {
                        m_classMethodDescriptors [m] = new MethodDescriptor (m_methodName, method.getDescriptor (cls), IMetadataConstants.METHOD_ABSTRACT_OR_NATIVE, m_methodBlockSizes, null, 0);
                    }
                    
                    if (trace2) m_log.trace2 ("visit", "skipped " + (method.isAbstract () ? "abstract" : "native") + " method");
                }
                else // this is a regular, non-<clinit> method that has bytecode:
                {
                    // reset first line:
                    m_methodFirstLine = 0;
                    
                    // set current method ID:
                    m_methodID = m;
                    
                    if (isClinit)
                    {
                        // if <clinit> found: note the ID but delay processing until the very end
                        m_clinitID = m;
                        if (trace2) m_log.trace2 ("visit", "<clinit> method delayed");
                    }
                    else
                    {
                        // visit attributes [skip visit (IAttributeCollection) method]:    
                        final IAttributeCollection attributes = method.getAttributes ();
                        final int attributeCount = attributes.size ();
                        for (int a = 0; a < attributeCount; ++ a)
                        {
                            final Attribute_info attribute = attributes.get (a);
                            attribute.accept (this, ctx);
                        }
                        
                        if (constructMetadata)
                        {
                            if ($assert.ENABLED) $assert.ASSERT (m_classBlockCounts [m_methodID] > 0, "invalid block count for method " + m_methodID + ": " + m_classBlockCounts [m_methodID]);
                            if ($assert.ENABLED) $assert.ASSERT (m_methodBlockSizes != null && m_methodBlockSizes.length == m_classBlockCounts [m_methodID], "invalid block sizes map for method " + m_methodID);
                            
                            final int [][] methodBlockMetadata = m_classBlockMetadata [m_methodID];
                            final int status = (methodBlockMetadata == null ? IMetadataConstants.METHOD_NO_LINE_NUMBER_TABLE : 0);
                            
                            m_classMethodDescriptors [m] = new MethodDescriptor (m_methodName, method.getDescriptor (cls), status, m_methodBlockSizes, methodBlockMetadata, m_methodFirstLine);
                        }
                    }                
                }
            }
        }
        
        // add <clinit> (and instrument if needed) [a <clinit> is always needed
        // even if there are no other instrumented method to act as a load hook]:
        
        final boolean instrumentClinit = false; // TODO: make use of this [to limit instrumentation to clinitHeader only], take into account whether we added and whether it is synthetic
        final Method_info clinit;
        
        if (m_clinitID >= 0)
        {
            // <clinit> existed in the original class: needs to be covered
            
            // m_clinitStatus = 0;
            clinit = methods.get (m_clinitID);
            
            m_classInstrMethodCount = originalMethodCount;
        }
        else
        {
            // there is no <clinit> defined by the original class: add one [and mark it synthetic]
            
            m_clinitStatus = IMetadataConstants.METHOD_ADDED;  // mark as added by us
            
            final int attribute_name_index = cls.addCONSTANT_Utf8 (Attribute_info.ATTRIBUTE_CODE, true);
            final int name_index = cls.addCONSTANT_Utf8 (IClassDefConstants.CLINIT_NAME, true);
            final int descriptor_index = cls.addCONSTANT_Utf8 ("()V", true);
            
            final IAttributeCollection attributes;
            
            if (MARK_ADDED_ELEMENTS_SYNTHETIC)
                attributes = ElementFactory.newAttributeCollection (2);
            else
                attributes = ElementFactory.newAttributeCollection (1);
            
            final CodeAttribute_info code = new CodeAttribute_info (attribute_name_index,
                0, 0,
                new byte [] {(byte) _return},
                AttributeElementFactory.newExceptionHandlerTable (0),
                ElementFactory.newAttributeCollection (0));
                
            attributes.add (code);
            
            if (MARK_ADDED_ELEMENTS_SYNTHETIC)
            {
                attributes.add (new SyntheticAttribute_info (m_syntheticStringIndex));
            }
            
            clinit = new Method_info (IAccessFlags.ACC_STATIC | IAccessFlags.ACC_PRIVATE, name_index, descriptor_index, attributes);
            
            m_clinitID = cls.addMethod (clinit);
            
            if (trace2) m_log.trace2 ("visit", "added synthetic <clinit> method");
            
            // TODO: this should exclude <clinit> if it were added by us
            m_classInstrMethodCount = originalMethodCount + 1;
        }
        
        if ($assert.ENABLED) $assert.ASSERT (m_classInstrMethodCount >= 0,
            "m_classInstrMethodCount not set");
        

        // visit <clinit>:
        {
            m_methodFirstLine = 0;
            m_methodID = m_clinitID;
            
            if (trace2) m_log.trace2 ("visit", (clinit.isSynthetic () ? "synthetic " : "") + "method #" + m_methodID + ": [<clinit>]");
            
            final IAttributeCollection attributes = clinit.getAttributes ();
            final int attributeCount = attributes.size ();
            for (int a = 0; a < attributeCount; ++ a)
            {
                final Attribute_info attribute = attributes.get (a);
                attribute.accept (this, ctx);
            }
        }

        // add pre-<clinit> method:
        
        {
            final int attribute_name_index = cls.addCONSTANT_Utf8 (Attribute_info.ATTRIBUTE_CODE, true);
            final int name_index = cls.addCONSTANT_Utf8 (PRECLINIT_METHOD_NAME, false);
            final int descriptor_index = cls.addCONSTANT_Utf8 ("()[[Z", false);
            
            final IAttributeCollection attributes;
            
            if (MARK_ADDED_ELEMENTS_SYNTHETIC)
                attributes = ElementFactory.newAttributeCollection (2);
            else
                attributes = ElementFactory.newAttributeCollection (1);
            
            final ByteArrayOStream buf = new ByteArrayOStream (PRECLINIT_INIT_CAPACITY);  
            {
                final int [] blockCounts = m_classBlockCounts;
                final int instrMethodCount = m_classInstrMethodCount; // actual number of methods to instrument may be less than the size of the block map 

                if ($assert.ENABLED) $assert.ASSERT (blockCounts != null && blockCounts.length >= instrMethodCount,
                    "invalid block count map");
                
                // new and set COVERAGE_FIELD:
                
                // push first dimension:
                CodeGen.push_int_value (buf, cls, instrMethodCount);
                
                // [stack +1]
                
                // new boolean [][]:
                final int type_index = cls.addClassref ("[[Z");
                buf.write4 (_multianewarray,
                            type_index >>> 8,    // indexbyte1
                            type_index,          // indexbyte2
                            1); // only one dimension created here
                
                // [stack +1]
                
                // clone array ref:
                buf.write4 (_dup,
                
                // [stack +2]
                
                // store in the static field
                            _putstatic,
                            m_coverageFieldrefIndex >>> 8,    // indexbyte1
                            m_coverageFieldrefIndex);          // indexbyte2
                
                // [stack +1]
                
                for (int m = 0; m < instrMethodCount; ++ m)
                {
                    final int blockCount = blockCounts [m]; 
                    if (blockCount > 0)
                    {
                        // clone array ref:
                        buf.write (_dup);
                        
                        // [stack +2]
                        
                        // push outer dim index:
                        CodeGen.push_int_value (buf, cls, m);
                        
                        // [stack +3]
                        
                        // push dim:
                        CodeGen.push_int_value (buf, cls, blockCount);
                        
                        // [stack +4]
                        
                        // newarray boolean []:
                        buf.write3 (_newarray,
                                    4, // "T_BOOLEAN"
                        
                        // add subarray to the outer array:
                                    _aastore);
                        
                        // [stack +1]
                    }
                }
                
                // [stack +1]
                
                {
                    // clone array ref
                    buf.write (_dup);
                    
                    // [stack +2]
                    
                    CodeGen.push_constant_index (buf, m_classNameConstantIndex);
                    
                    // [stack +3]

                    buf.write3 (_ldc2_w,
                                m_stampIndex >>> 8,    // indexbyte1
                                m_stampIndex);         // indexbyte2
                    
                    // [stack +5]
                    
                    buf.write3 (_invokestatic,
                                m_registerMethodrefIndex >>> 8,    // indexbyte1
                                m_registerMethodrefIndex);         // indexbyte2
                    
                    // [stack +1]
                }
                
                // pop and return extra array ref:
                buf.write (_areturn);
                
                // [stack +0]
            }

            final CodeAttribute_info code = new CodeAttribute_info (attribute_name_index,
                5, 0, // adjust constants if the bytecode emitted above changes
                EMPTY_BYTE_ARRAY,
                AttributeElementFactory.newExceptionHandlerTable (0),
                ElementFactory.newAttributeCollection (0));
            
            code.setCode (buf.getByteArray (), buf.size ());
                
            attributes.add (code);
            
            if (MARK_ADDED_ELEMENTS_SYNTHETIC)
            {
                attributes.add (new SyntheticAttribute_info (m_syntheticStringIndex));
            }
            
            final Method_info preclinit = new Method_info (IAccessFlags.ACC_STATIC | IAccessFlags.ACC_PRIVATE, name_index, descriptor_index, attributes);
            cls.addMethod (preclinit);
            
            if (trace2) m_log.trace2 ("visit", "added synthetic pre-<clinit> method");
        }

        
        if (constructMetadata)
        {
            if ($assert.ENABLED) $assert.ASSERT (m_classBlockCounts [m_methodID] > 0, "invalid block count for method " + m_methodID + " (" + IClassDefConstants.CLINIT_NAME + "): " + m_classBlockCounts [m_methodID]);
            if ($assert.ENABLED) $assert.ASSERT (m_methodBlockSizes != null && m_methodBlockSizes.length == m_classBlockCounts [m_methodID], "invalid block sizes map for method " + m_methodID);
            
            final int [][] methodBlockMetadata = m_classBlockMetadata [m_methodID];
            m_clinitStatus |= (methodBlockMetadata == null ? IMetadataConstants.METHOD_NO_LINE_NUMBER_TABLE : 0);
            
            // TODO: this still does not process not added/synthetic case  
            
            if ((m_clinitStatus & IMetadataConstants.METHOD_ADDED) == 0)
                m_classMethodDescriptors [m_methodID] = new MethodDescriptor (IClassDefConstants.CLINIT_NAME, clinit.getDescriptor (cls), m_clinitStatus, m_methodBlockSizes, methodBlockMetadata, m_methodFirstLine);
        }
        
        return ctx;
    }


    public Object visit (final IAttributeCollection attributes, Object ctx)
    {
        for (int a = 0, aCount = attributes.size (); a < aCount; ++ a)
        {
            // TODO: define a global way to set the mask set of attrs to be visited
            attributes.get (a).accept (this, ctx);
        } 

        return ctx;
    }
    
    
    // IAttributeVisitor:

    public Object visit (final CodeAttribute_info attribute, final Object ctx)
    {
        final boolean trace2 = m_log.atTRACE2 ();
        final boolean trace3 = m_log.atTRACE3 ();
        
        final byte [] code = attribute.getCode ();
        final int codeSize = attribute.getCodeSize ();
        
        if (trace2) m_log.trace2 ("visit", "code attribute for method #" + m_methodID + ": size = " + codeSize);
        
        final IntSet leaders = new IntSet ();
        
        // instructionMap.get(ip) is the number of instructions in code[0-ip)
        // [this map will include a mapping for code length as well]
        final IntIntMap /* int(ip)->instr count */ instructionMap = new IntIntMap ();
        
        // add first instruction and all exc handler start pcs:
        leaders.add (0); 
        
        final IExceptionHandlerTable exceptions = attribute.getExceptionTable ();
        final int exceptionCount = exceptions.size ();
        for (int e = 0; e < exceptionCount; ++ e)
        {
            final Exception_info exception = exceptions.get (e);
            leaders.add (exception.m_handler_pc);
        }
        
        
        final IntObjectMap branches = new IntObjectMap ();
        
        // determine block leaders [an O(code length) loop]:
        
        boolean branch = false;
        boolean wide = false;

        int instructionCount = 0;
        instructionMap.put (0, 0);
        
        for (int ip = 0; ip < codeSize; )
        {
            final int opcode = 0xFF & code [ip];
            int size = 0; // will be set to -<real size> for special cases in the switch below 
            
            //if (trace3) m_log.trace3 ("parse", MNEMONICS [opcode]);
            // "visitor.visit (opcode, wide, ip, null)":
            
            { // "opcode visit" logic:
                
                int iv, ov;
                
                if (branch)
                {
                    // previous instruction was a branch: this one is a leader
                    leaders.add (ip);
                    branch = false;
                }
                
                switch (opcode)
                {
                    case _ifeq:
                    case _iflt:
                    case _ifle:
                    case _ifne:
                    case _ifgt:
                    case _ifge:
                    case _ifnull:
                    case _ifnonnull:
                    case _if_icmpeq:
                    case _if_icmpne:
                    case _if_icmplt:
                    case _if_icmpgt:
                    case _if_icmple:
                    case _if_icmpge:
                    case _if_acmpeq:
                    case _if_acmpne:
                    {
                        //ov = getI2 (code, ip + 1);
                        int scan = ip + 1;
                        ov = (code [scan] << 8) | (0xFF & code [++ scan]);
                        
                        final int target = ip + ov;
                        leaders.add (target); 
                        
                        branches.put (ip, new IFJUMP2 (opcode, target));
                        branch = true;
                    }
                    break;


                    case _goto:
                    case _jsr:
                    {
                        //ov = getI2 (code, ip + 1);
                        int scan = ip + 1;
                        ov = (code [scan] << 8) | (0xFF & code [++ scan]);
                        
                        final int target = ip + ov;
                        leaders.add (target); 
                        
                        branches.put (ip, new JUMP2 (opcode, target));
                        branch = true;
                    }
                    break;


                    case _lookupswitch:
                    {
                        int scan = ip + 4 - (ip & 3); // eat padding
                        
                        ov = (code [scan] << 24) | ((0xFF & code [++ scan]) << 16) | ((0xFF & code [++ scan]) << 8) | (0xFF & code [++ scan]);
                        leaders.add (ip + ov);
                        
                        //final int npairs = getU4 (code, scan);
                        //scan += 4;
                        final int npairs = ((0xFF & code [++ scan]) << 24) | ((0xFF & code [++ scan]) << 16) | ((0xFF & code [++ scan]) << 8) | (0xFF & code [++ scan]);
                        
                        final int [] keys = new int [npairs];
                        final int [] targets = new int [npairs + 1];
                        targets [0] = ip + ov;
                        
                        for (int p = 0; p < npairs; ++ p)
                        {
                            //iv = getI4 (code, scan);
                            //scan += 4;
                            iv = (code [++ scan] << 24) | ((0xFF & code [++ scan]) << 16) | ((0xFF & code [++ scan]) << 8) | (0xFF & code [++ scan]);
                            keys [p] = iv;
                            
                            
                            //ov = getI4 (code, scan);
                            //scan += 4;
                            ov = (code [++ scan] << 24) | ((0xFF & code [++ scan]) << 16) | ((0xFF & code [++ scan]) << 8) | (0xFF & code [++ scan]);
                            targets [p + 1] = ip + ov;
                            leaders.add (ip + ov);
                        }
                        
                        branches.put (ip, new LOOKUPSWITCH (keys, targets));
                        branch = true;
                        
                        size = ip - scan - 1; // special case
                    }
                    break;

                    
                    case _tableswitch:
                    {
                        int scan = ip + 4 - (ip & 3); // eat padding
                        
                        ov = (code [scan] << 24) | ((0xFF & code [++ scan]) << 16) | ((0xFF & code [++ scan]) << 8) | (0xFF & code [++ scan]);
                        leaders.add (ip + ov);
                                                
                        //final int low = getI4 (code, scan + 4);
                        final int low = (code [++ scan] << 24) | ((0xFF & code [++ scan]) << 16) | ((0xFF & code [++ scan]) << 8) | (0xFF & code [++ scan]);
                        //final int high = getI4 (code, scan + 8);
                        //scan += 12;
                        final int high = (code [++ scan] << 24) | ((0xFF & code [++ scan]) << 16) | ((0xFF & code [++ scan]) << 8) | (0xFF & code [++ scan]);
                        
                        final int [] targets = new int [high - low + 2];
                        targets [0] = ip + ov;
                        
                        for (int index = low; index <= high; ++ index)
                        {
                            //ov = getI4 (code, scan);
                            ov = (code [++ scan] << 24) | ((0xFF & code [++ scan]) << 16) | ((0xFF & code [++ scan]) << 8) | (0xFF & code [++ scan]);
                            targets [index - low + 1] = ip + ov;
                            leaders.add (ip + ov);
                            //scan += 4;
                        }

                        branches.put (ip, new TABLESWITCH (low, high, targets));
                        branch = true;
                        
                        size = ip - scan - 1; // special case
                    }
                    break;
                        

                    case _goto_w:
                    case _jsr_w:
                    {
                        int scan = ip + 1;
                        //ov = getI4 (code, ip + 1);
                        ov = (code [scan] << 24) | ((0xFF & code [++ scan]) << 16) | ((0xFF & code [++ scan]) << 8) | (0xFF & code [++ scan]);
                        final int target = ip + ov;
                        
                        leaders.add (target);
                        
                        branches.put (ip, new JUMP4 (opcode, target));
                        branch = true;
                    }
                    break;


                    case _ret:
                    {
                        int scan = ip + 1;
                        iv = wide ? (((0xFF & code [scan]) << 8) | (0xFF & code [++ scan])) : (0xFF & code [scan]);
                        
                        branches.put (ip, new RET (opcode, iv));
                        branch = true;
                    } 
                    break; 


                    case _athrow:
                    case _ireturn:
                    case _lreturn:
                    case _freturn:
                    case _dreturn:
                    case _areturn:
                    case _return:
                    {
                        branches.put (ip, new TERMINATE (opcode));
                        branch = true;
                    }
                    break;
                    
                } // end of switch
                
            } // end of processing the current opcode
            
            
            // shift to the next instruction [this is the only block that adjusts 'ip']:
            
            if (size == 0)
                size = (wide ? WIDE_SIZE : NARROW_SIZE) [opcode];
            else
                size = -size;
            
            ip += size;
            wide = (opcode == _wide);
            
            instructionMap.put (ip, ++ instructionCount);
            
        } // end of for
        
        
        // split 'code' into an ordered list of basic blocks [O(block count) loops]:
        
        final int blockCount = leaders.size ();
        if (trace2) m_log.trace2 ("visit", "method contains " + blockCount + " basic blocks");
        
        final BlockList blocks = new BlockList (blockCount);
        
        final int [] _leaders = new int [blockCount + 1]; // room for end-of-code leader at the end 
        leaders.values (_leaders, 0);
        _leaders [blockCount] = codeSize;
        
        Arrays.sort (_leaders);
        
        final int [] _branch_locations = branches.keys (); 
        Arrays.sort (_branch_locations);
        
        final IntIntMap leaderToBlockID = new IntIntMap (_leaders.length);
        
        if (m_metadata)
        {
            // help construct a MethodDescriptor for the current method:
            
            m_methodBlockSizes = new int [blockCount];
            m_methodBlockOffsets = _leaders;
        }

        // compute signature even if metadata is not needed (because the instrumented
        // classdef uses it):
        consumeSignatureData (m_methodID, _leaders);
        
        // pass 1:
        
        final int [] intHolder = new int [1];
        int instr_count = 0, prev_instr_count;
        
        for (int bl = 0, br = 0; bl < blockCount; ++ bl)
        {
            final Block block = new Block ();
            blocks.m_blocks.add (block);
            
            final int leader = _leaders [bl];
            
            block.m_first = leader; // m_first set
            leaderToBlockID.put (leader, bl);
            
            final int next_leader = _leaders [bl + 1];
            boolean branchDelimited = false;
            
            prev_instr_count = instr_count;

            if (_branch_locations.length > br)
            {
                final int next_branch_location = _branch_locations [br];
                if (next_branch_location < next_leader)
                {
                    branchDelimited = true;
                    
                    block.m_length = next_branch_location - leader; // m_length set
                    
                    if ($assert.ENABLED)
                        $assert.ASSERT (instructionMap.get (next_branch_location, intHolder), "no mapping for " + next_branch_location);
                    else
                        instructionMap.get (next_branch_location, intHolder);
                        
                    instr_count = intHolder [0] + 1; // [+ 1 for the branch]
                     
                    block.m_branch = (Branch) branches.get (next_branch_location);
                    block.m_branch.m_parentBlockID = bl; // m_branch set
                    
                    ++ br;
                }
            }
            
            if (! branchDelimited)
            {
                block.m_length = next_leader - leader; // m_length set
                
                if ($assert.ENABLED)
                    $assert.ASSERT (instructionMap.get (next_leader, intHolder), "no mapping for " + next_leader);
                else
                    instructionMap.get (next_leader, intHolder);
                
                instr_count = intHolder [0];
            }
            
            block.m_instrCount = instr_count - prev_instr_count; // m_instrCount set
            
            if ($assert.ENABLED) $assert.ASSERT (block.m_length == 0 || block.m_instrCount > 0, "invalid instr count for block " + bl + ": " + block.m_instrCount);
            if (m_metadata) m_methodBlockSizes [bl] = block.m_instrCount; 
        }
        
        // pass 2:
        
        final Block [] _blocks = (Block []) blocks.m_blocks.toArray (new Block [blockCount]);
        
        for (int l = 0; l < blockCount; ++ l)
        {
            final Block block = _blocks [l];
             
            if (block.m_branch != null)
            {
                final int [] targets = block.m_branch.m_targets;
                if (targets != null)
                {
                    for (int t = 0, targetCount = targets.length; t < targetCount; ++ t)
                    {
                        // TODO: HACK ! convert block absolute offsets to block IDs:
                        
                        if ($assert.ENABLED)
                            $assert.ASSERT (leaderToBlockID.get (targets [t], intHolder), "no mapping for " + targets [t]);
                        else
                            leaderToBlockID.get (targets [t], intHolder);
                            
                        targets [t] = intHolder [0];
                    }
                }
            }
        }

        
        // update block count map [used later by <clinit> visit]:
        m_classBlockCounts [m_methodID] = blockCount;
        
        // actual basic block instrumentation:
        {
            if (trace2) m_log.trace2 ("visit", "instrumenting... ");
            
            // determine the local var index for the var that will alias COVERAGE_FIELD:
            final int localVarIndex = attribute.m_max_locals ++;
            
            if (m_methodID == m_clinitID) // note: m_clinitID can be -1 if <clinit> has not been visited yet
            {
                 // add a long stamp constant after all the original methods have been visited:
             
                m_stampIndex = m_cls.getConstants ().add (new CONSTANT_Long_info (m_classSignature));
                
                blocks.m_header = new clinitHeader (this, localVarIndex);
            }
            else
                blocks.m_header = new methodHeader (this, localVarIndex);
            
            int headerMaxStack = blocks.m_header.maxstack ();
            int methodMaxStack = 0;
            
            for (int l = 0; l < blockCount; ++ l)
            {
                final Block block = _blocks [l];
                
                final CodeSegment insertion = new BlockSegment (this, localVarIndex, l);
                block.m_insertion = insertion;
                
                final int insertionMaxStack = insertion.maxstack (); 
                if (insertionMaxStack > methodMaxStack)
                    methodMaxStack = insertionMaxStack;
            }
            
            // update maxstack as needed [it can only grow]:
            {
                final int oldMaxStack = attribute.m_max_stack;
                
                attribute.m_max_stack += methodMaxStack; // this is not precise, but still need to add because the insertion may be happening at the old maxstack point
                
                if (headerMaxStack > attribute.m_max_stack)
                attribute.m_max_stack = headerMaxStack;
                
                if (trace3) m_log.trace3 ("visit", "increasing maxstack by " + (attribute.m_max_stack - oldMaxStack));
            }
            
            if ($assert.ENABLED) $assert.ASSERT (blocks.m_header != null, "header not set");
        }
        

        // assemble all blocks into an instrumented code block:
        if (trace2) m_log.trace2 ("visit", "assembling... ");
        
        int newcodeCapacity = codeSize << 1;
        if (newcodeCapacity < EMIT_CTX_MIN_INIT_CAPACITY) newcodeCapacity = EMIT_CTX_MIN_INIT_CAPACITY;

        final ByteArrayOStream newcode = new ByteArrayOStream (newcodeCapacity); // TODO: empirical capacity
        final EmitCtx emitctx = new EmitCtx (blocks, newcode);
        
        // create a jump adjustment map:
        final int [] jumpAdjOffsets = new int [blockCount]; // room for initial 0  + (blockCount - 1)
        final int [] jumpAdjMap = new int [jumpAdjOffsets.length]; // room for initial 0  + (blockCount - 1)
        
        if ($assert.ENABLED) $assert.ASSERT (jumpAdjOffsets.length == jumpAdjMap.length,
            "jumpAdjOffsets and jumpAdjMap length mismatch");
        
        // header:
        blocks.m_header.emit (emitctx);
        // jumpAdjOffsets [0] = 0: redundant
        jumpAdjMap [0] = emitctx.m_out.size ();
        
        // rest of blocks:
        for (int l = 0; l < blockCount; ++ l)
        {
            final Block block = _blocks [l];
            
            if (l + 1 < blockCount)
            {
                jumpAdjOffsets [l + 1] = _blocks [l].m_first + _blocks [l].m_length; // implies the insertion goes just before the branch
            }
            
            block.emit (emitctx, code);
            
            // TODO: this breaks if code can shrink:
            if (l + 1 < blockCount)
            {
                jumpAdjMap [l + 1] = emitctx.m_out.size () - _blocks [l + 1].m_first;
            }
        }
        
        m_methodJumpAdjOffsets = jumpAdjOffsets;
        m_methodJumpAdjValues = jumpAdjMap;
        
        if (trace3)
        {
            final StringBuffer s = new StringBuffer ("jump adjustment map:" + EOL);
            for (int a = 0; a < jumpAdjOffsets.length; ++ a)
            {
                s.append ("    " + jumpAdjOffsets [a] + ": +" + jumpAdjMap [a]);
                if (a < jumpAdjOffsets.length - 1) s.append (EOL);
            }
            
            m_log.trace3 ("visit", s.toString ());
        }
        
        final byte [] _newcode = newcode.getByteArray (); // note: not cloned 
        final int _newcodeSize = newcode.size ();
         
        // [all blocks have had their m_first adjusted]
        
        // backpatching pass:        
        if (trace3) m_log.trace3 ("visit", "backpatching " + emitctx.m_backpatchQueue.size () + " ip(s)");
        
        for (Iterator i = emitctx.m_backpatchQueue.iterator (); i.hasNext (); )
        {
            final int [] patchData = (int []) i.next ();
            int ip = patchData [1];
            
            if ($assert.ENABLED) $assert.ASSERT (patchData != null, "null patch data for ip " + ip);
            
            final int jump = _blocks [patchData [3]].m_first - patchData [2];
            if ($assert.ENABLED) $assert.ASSERT (jump > 0, "negative backpatch jump offset " + jump + " for ip " + ip);
            
            switch (patchData [0])
            {
                case 4:
                {
                    _newcode [ip ++] = (byte) (jump >>> 24);
                    _newcode [ip ++] = (byte) (jump >>> 16);
                    
                } // *FALL THROUGH*
                
                case 2:
                {
                    _newcode [ip ++] = (byte) (jump >>> 8);
                    _newcode [ip] = (byte) jump;
                }
            }
        }
        
        attribute.setCode (_newcode, _newcodeSize);
        if (trace2) m_log.trace2 ("visit", "method assembled into " + _newcodeSize + " code bytes");

        
        // adjust bytecode offsets in the exception table:
        final IExceptionHandlerTable exceptionTable = attribute.getExceptionTable ();
        for (int e = 0; e < exceptionTable.size (); ++ e)
        {
            final Exception_info exception = exceptionTable.get (e);
            
            int adjSegment = lowbound (jumpAdjOffsets, exception.m_start_pc);
            exception.m_start_pc += jumpAdjMap [adjSegment];
            
            adjSegment = lowbound (jumpAdjOffsets, exception.m_end_pc);
            exception.m_end_pc += jumpAdjMap [adjSegment];
            
            adjSegment = lowbound (jumpAdjOffsets, exception.m_handler_pc);
            exception.m_handler_pc += jumpAdjMap [adjSegment];
        }

        
        // visit other nested attributes [LineNumberAttribute, etc]:    
        final IAttributeCollection attributes = attribute.getAttributes ();
        final int attributeCount = attributes.size ();
        for (int a = 0; a < attributeCount; ++ a)
        {
            final Attribute_info nested = attributes.get (a);
            nested.accept (this, ctx);
        }
        
        return ctx;
    }
    

    public Object visit (final LineNumberTableAttribute_info attribute, final Object ctx)
    {
        final boolean trace2 = m_log.atTRACE2 ();
        final boolean trace3 = m_log.atTRACE3 (); 
        if (trace2) m_log.trace2 ("visit", "attribute: [" + attribute.getName (m_cls) + "]");
        
        final int lineCount = attribute.size ();
        
        if (m_metadata)
        {
            if (trace2) m_log.trace2 ("visit", "processing line number table for metadata...");
            
            final int blockCount = m_classBlockCounts [m_methodID];
            if ($assert.ENABLED) $assert.ASSERT (blockCount > 0, "invalid method block count for method " + m_methodID);
            
            final int [][] blockLineMap = new int [blockCount][];
            
            if ($assert.ENABLED) $assert.ASSERT (blockCount + 1 == m_methodBlockOffsets.length,
                    "invalid m_methodBlockOffsets");
            
            if (lineCount == 0)
            {
                for (int bl = 0; bl < blockCount; ++ bl)
                    blockLineMap [bl] = EMPTY_INT_ARRAY;
            }
            else
            {
                // TODO: this code does not work if there are multiple LineNumberTableAttribute attributes for the method

                final LineNumber_info [] sortedLines = new LineNumber_info [attribute.size ()];
                
                for (int l = 0; l < lineCount; ++ l)
                {
                    final LineNumber_info line = attribute.get (l);
                    sortedLines [l] = line;
                }
                
                Arrays.sort (sortedLines, LINE_NUMBER_COMPARATOR);
                
                // construct block->line mapping: TODO: is the loop below the fastest it can be done?
                
                final int [] methodBlockOffsets = m_methodBlockOffsets;
                
                LineNumber_info line = sortedLines [0]; // never null
                LineNumber_info prev_line = null;
                
                // remember the first line:
                m_methodFirstLine = line.m_line_number;
                
                for (int bl = 0, l = 0; bl < blockCount; ++ bl)
                {                   
                    final IntSet blockLines = new IntSet ();
                    
                    if ((prev_line != null) && (line.m_start_pc > methodBlockOffsets [bl]))
                    {
                        blockLines.add (prev_line.m_line_number);
                    }
                    
                    while (line.m_start_pc < methodBlockOffsets [bl + 1])
                    {
                        blockLines.add (line.m_line_number);
                        
                        if (l == lineCount - 1)
                            break;
                        else
                        {
                            prev_line = line;
                            line = sortedLines [++ l]; // advance to the next line
                        }
                    }
                    
                    blockLineMap [bl] = blockLines.values ();
                }                
            }
            
            m_classBlockMetadata [m_methodID] = blockLineMap;
            
            if (trace3)
            {
                StringBuffer s = new StringBuffer ("block-line map for method #" + m_methodID + ":");
                for (int bl = 0; bl < blockCount; ++ bl)
                {
                    s.append (EOL);
                    s.append ("    block " + bl + ": ");
                    
                    final int [] lines = blockLineMap [bl];
                    for (int l = 0; l < lines.length; ++ l)
                    {
                        if (l != 0) s.append (", ");
                        s.append (lines [l]);
                    }
                }
                
                m_log.trace3 ("visit", s.toString ());
            }
        }
        
        for (int l = 0; l < lineCount; ++ l)
        {
            final LineNumber_info line = attribute.get (l);
            
            // TODO: make this faster using either table assist or the sorted array in 'sortedLines'
            
            // adjust bytecode offset for line number mapping:
            int adjSegment = lowbound (m_methodJumpAdjOffsets, line.m_start_pc);                
            line.m_start_pc += m_methodJumpAdjValues [adjSegment];
        }
        
        return ctx;
    }
    
    // TODO: line var table as well
    

    // no-op visits:

    public Object visit (final ExceptionsAttribute_info attribute, final Object ctx)
    {
        return ctx;
    }
    
    public Object visit (final ConstantValueAttribute_info attribute, final Object ctx)
    {
        return ctx;
    }
    
    public Object visit (final SourceFileAttribute_info attribute, final Object ctx)
    {
        m_classSrcFileName = attribute.getSourceFile (m_cls).m_value;

        return ctx;
    }

    public Object visit (final SyntheticAttribute_info attribute, final Object ctx)
    {
        return ctx;
    }
    
    public Object visit (final BridgeAttribute_info attribute, final Object ctx)
    {
        return ctx;
    }
    
    public Object visit (final InnerClassesAttribute_info attribute, final Object ctx)
    {
        return ctx;
    }
    
    public Object visit (final GenericAttribute_info attribute, final Object ctx)
    {
        return ctx;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private static final class BlockList
    {
        BlockList ()
        {
            m_blocks = new ArrayList ();
        }
        
        BlockList (final int capacity)
        {
            m_blocks = new ArrayList (capacity);
        }
        
        final List /* Block */ m_blocks; // TODO: might as well use an array here?
        CodeSegment m_header;
        
    } // end of nested class 
    
    
    private static final class Block
    {
        int m_first;    // inclusive offset of the leader instruction [first instr in the block]
        //int m_last;     // exclusive offset of the last non-branch instruction [excludes possible control transfer at the end]
        int m_length;   // excluding the branch statement [can be 0]
        int m_instrCount; // size in instructions, including the [optional] original branch; [m_insertion is not counted] 
        
        // NOTE: it is possible that m_first == m_last [the block is empty except for a possible control transfer instr]
        
//        public int maxlength ()
//        {
//            // TODO: cache
//            return m_length
////                + (m_insertion != null ? m_insertion.maxlength () : 0)
//                + (m_branch != null ? m_branch.maxlength () : 0);
//        }
        
        /**
         * When this is called, all previous blocks have been written out and 
         * their m_first have been updated.
         */
        void emit (final EmitCtx ctx, final byte [] code) // TODO: move 'code' into 'ctx'
        {
            final ByteArrayOStream out = ctx.m_out;
            final int first = m_first;
            
            m_first = out.size (); // update position to be within new code array
            
            for (int i = 0, length = m_length; i < length; ++ i)
            {
                out.write (code [first + i]);
            }
            
            if (m_insertion != null)
                m_insertion.emit (ctx);
            
            if (m_branch != null)
                m_branch.emit (ctx);
        }
        
        public CodeSegment m_insertion;
        public Branch m_branch; // falling through is implied by this being null
        
    } // end of nested class
    
    
    static final class EmitCtx
    {
        // TODO: profile to check that ByteArrayOStream.write() is not the bottleneck
        
        EmitCtx (final BlockList blocks, final ByteArrayOStream out)
        {
            m_blocks = blocks;
            m_out = out;
            
            m_backpatchQueue = new ArrayList ();
        }
        
        final BlockList m_blocks;
        final ByteArrayOStream m_out;
        final List /* int[4] */ m_backpatchQueue;
        
    } // end of nested class
    
    
    /**
     * A Branch does not add any maxlocals/maxstack requirements.
     */
    static abstract class Branch
    {
        protected Branch (final int opcode, final int [] targets)
        {
            m_opcode = (byte) opcode;
            m_targets = targets;
        }
        
        /*
         * Called when targets are block IDs, before emitting. 
         */
        int maxlength () { return 1; }
        
        abstract void emit (EmitCtx ctx);
        
        // TODO: this method must signal when it is necessary to switch to long jump form
        protected final void emitJumpOffset2 (final EmitCtx ctx, final int ip, final int targetBlockID)
        {
            final ByteArrayOStream out = ctx.m_out;
            
            if (targetBlockID <= m_parentBlockID)
            {
                // backwards branch:
                final int jumpOffset = ((Block) ctx.m_blocks.m_blocks.get (targetBlockID)).m_first - ip;
                
                out.write2 (jumpOffset >>> 8,   // targetbyte1
                            jumpOffset);         // targetbyte2
            }
            else
            {
                final int jumpOffsetLocation = out.size (); 
                
                // else write out zeros and submit for backpatching:
                out.write2 (0,
                            0);
                
                ctx.m_backpatchQueue.add (new int [] {2, jumpOffsetLocation, ip, targetBlockID});
            }
        }
        
        protected final void emitJumpOffset4 (final EmitCtx ctx, final int ip, final int targetBlockID)
        {
            final ByteArrayOStream out = ctx.m_out;
            
            if (targetBlockID <= m_parentBlockID)
            {
                // backwards branch:
                final int jumpOffset = ((Block) ctx.m_blocks.m_blocks.get (targetBlockID)).m_first - ip;
                
                out.write4 (jumpOffset >>> 24,    // targetbyte1
                            jumpOffset >>> 16,    // targetbyte2
                            jumpOffset >>> 8,     // targetbyte3
                            jumpOffset);           // targetbyte4
            }
            else
            {
                final int jumpOffsetLocation = out.size (); 
                
                // else write out zeros and submit for backpatching:
                out.write4 (0,
                            0,
                            0,
                            0);
                
                ctx.m_backpatchQueue.add (new int [] {4, jumpOffsetLocation, ip, targetBlockID});
            }
        } 
        
        final byte m_opcode;
        final int [] m_targets; // could be code offsets or block IDs
        
        int m_parentBlockID;
        
    } // end of nested class    
    
    
    // TODO: these could be static instance-pooled
    static final class TERMINATE extends Branch // _[x]return, _athrow
    {
        TERMINATE (final int opcode)
        {
            super (opcode, null);
        }      
        
        int length () { return 1; }
        
        void emit (final EmitCtx ctx)
        {
            ctx.m_out.write (m_opcode);
        }
        
    } // end of nested class
    
    
    static final class RET extends Branch // [wide] ret
    {
        RET (final int opcode, final int varindex)
        {
            super (opcode, null);
            m_varindex = varindex;
        }      
        
        int length () { return (m_varindex <= 0xFF) ? 2 : 3; }
        
        void emit (final EmitCtx ctx)
        {
            final ByteArrayOStream out = ctx.m_out;
            
            if (m_varindex <= 0xFF)
            {
                out.write2 (m_opcode,
                            m_varindex);  // indexbyte
            }
            else
            {
                out.write4 (_wide,
                            m_opcode,
                            m_varindex >>> 8,   // indexbyte1
                            m_varindex);         // indexbyte2
            }
        }
        
        final int m_varindex;
        
    } // end of nested class
    
    
    static final class JUMP2 extends Branch // _goto, _jsr
    {
        JUMP2 (final int opcode, final int target)
        {
            super (opcode, new int [] {target});
        }
        
        int maxlength () { return 5; }
        
        void emit (final EmitCtx ctx)
        {
            final ByteArrayOStream out = ctx.m_out;
            final int targetBlockID = m_targets [0];
            final int ip = out.size ();
            
            // TODO: switch to 4-byte long form if jump > 32k
            
            out.write (m_opcode);
            emitJumpOffset2 (ctx, ip, targetBlockID);
        }
        
    } // end of nested class
    
    
    static final class JUMP4 extends Branch // _goto_w, _jsr_w
    {
        JUMP4 (final int opcode, final int target)
        {
            super (opcode, new int [] {target});
        }
        
        int maxlength () { return 5; }
        
        void emit (final EmitCtx ctx)
        {
            final ByteArrayOStream out = ctx.m_out;
            final int targetBlockID = m_targets [0];
            final int ip = out.size ();
            
            out.write (m_opcode);
            emitJumpOffset4 (ctx, ip, targetBlockID);
        }
        
    } // end of nested class
    
    
    static final class IFJUMP2 extends Branch // _ifxxx
    {
        IFJUMP2 (final int opcode, final int target)
        {
            super (opcode, new int [] {target});
        }
        
        int maxlength () { return 8; }
        
        void emit (final EmitCtx ctx)
        {
            final ByteArrayOStream out = ctx.m_out;
            final int targetBlockID = m_targets [0];
            final int ip = out.size ();
            
            // TODO: switch to 8-byte long form if jump > 32k
            
            out.write (m_opcode);
            emitJumpOffset2 (ctx, ip, targetBlockID);
        }
        
    } // end of nested class

    
    static final class LOOKUPSWITCH extends Branch
    {
        LOOKUPSWITCH (final int [] keys, final int [] targets /* first one is default */)
        {
            super (_lookupswitch, targets);
            m_keys = keys;
        }
        
        int maxlength () { return 12 + (m_keys.length << 3); }
        
        void emit (final EmitCtx ctx)
        {
            final ByteArrayOStream out = ctx.m_out;
            final int ip = out.size ();
            
            out.write (m_opcode);
            
            // padding bytes:
            for (int p = 0, padCount = 3 - (ip & 3); p < padCount; ++ p) out.write (0);
             
            // default:
            emitJumpOffset4 (ctx, ip, m_targets [0]);
            
            // npairs count:
            final int npairs = m_keys.length;
            out.write4 (npairs >>> 24,  // byte1
                        npairs >>> 16,  // byte2
                        npairs >>> 8,   // byte3
                        npairs);        // byte4
            
            // keyed targets:
            for (int t = 1; t < m_targets.length; ++ t)
            {
                final int key = m_keys [t - 1];
                out.write4 (key >>> 24,  // byte1
                            key >>> 16,  // byte2
                            key >>> 8,   // byte3
                            key);         // byte4

                // key target:
                emitJumpOffset4 (ctx, ip, m_targets [t]); 
            }
        }
        
        final int [] m_keys;
        
    } // end of nested class


    static final class TABLESWITCH extends Branch
    {
        TABLESWITCH (final int low, final int high, final int [] targets /* first one is default */)
        {
            super (_tableswitch, targets);
            m_low = low;
            m_high = high;
        }
        
        int maxlength () { return 12 + (m_targets.length << 2); }
        
        void emit (final EmitCtx ctx)
        {
            final ByteArrayOStream out = ctx.m_out;
            final int ip = out.size ();
            
            // TODO: switch to long form for any jump > 32k
            
            out.write (m_opcode);
            
            // padding bytes:
            for (int p = 0, padCount = 3 - (ip & 3); p < padCount; ++ p) out.write (0);
             
            // default:
            emitJumpOffset4 (ctx, ip, m_targets [0]);
                        
            // low, high:
            final int low = m_low;
            out.write4 (low >>> 24,  // byte1
                        low >>> 16,  // byte2
                        low >>> 8,   // byte3
                        low);        // byte4
            
            final int high = m_high;
            out.write4 (high >>> 24,  // byte1
                        high >>> 16,  // byte2
                        high >>> 8,   // byte3
                        high);        // byte4
                        
            // targets:
            for (int t = 1; t < m_targets.length; ++ t)
            {
                // key target:
                emitJumpOffset4 (ctx, ip, m_targets [t]); 
            }
        }
            
        final int m_low, m_high;
            
    } // end of nested class
    
    
    /**
     * TODO: CodeSegment right now must be 100% position-independent code;
     * otherwise it must follow maxlengtt() Branch pattern... 
     */
    static abstract class CodeSegment
    {
        CodeSegment (final InstrVisitor visitor)
        {
            m_visitor = visitor; // TODO: will this field be used?
        }
        
        abstract int length ();
        abstract int maxstack ();
        abstract void emit (EmitCtx ctx);
        
        
        final InstrVisitor m_visitor;
        
    } // end of nested class
    
    
    static final class clinitHeader extends CodeSegment
    {
        clinitHeader (final InstrVisitor visitor, final int localVarIndex)
        {
            super (visitor);
            final ByteArrayOStream buf = new ByteArrayOStream (CLINIT_HEADER_INIT_CAPACITY); 
            m_buf = buf;
            
            final ClassDef cls = visitor.m_cls;
            
            final int [] blockCounts = visitor.m_classBlockCounts;
            final int instrMethodCount = visitor.m_classInstrMethodCount; // actual number of methods to instrument may be less than the size of the block map 
            if ($assert.ENABLED) $assert.ASSERT (blockCounts != null && blockCounts.length >= instrMethodCount,
                "invalid block count map");
            
            final int coverageFieldrefIndex = visitor.m_coverageFieldrefIndex;
            final int preclinitMethodrefIndex = visitor.m_preclinitMethodrefIndex;
            final int classNameConstantIndex = visitor.m_classNameConstantIndex;
            
            if ($assert.ENABLED)
            {
                $assert.ASSERT (coverageFieldrefIndex > 0, "invalid coverageFieldrefIndex");
                $assert.ASSERT (preclinitMethodrefIndex > 0, "invalid registerMethodrefIndex");
                $assert.ASSERT (classNameConstantIndex > 0, "invalid classNameConstantIndex");
            }

            // init and load COVERAGE_FIELD:   
            buf.write3 (_invokestatic,
                        preclinitMethodrefIndex >>> 8,    // indexbyte1
                        preclinitMethodrefIndex);         // indexbyte2

            // [stack +1]

            // TODO: disable this when there are no real blocks following?
            // [in general, use a different template when this method contains a single block]

            // TODO: if this method has been added by us, do not instrument its blocks
            
            // push int literal equal to 'methodID' [for the parent method]:
            CodeGen.push_int_value (buf, cls, visitor.m_methodID);
            
            // [stack +2]
            
            // push subarray reference:
            buf.write (_aaload);
            
            // [stack +1]
            
            // store it in alias var:
            CodeGen.store_local_object_var (buf, localVarIndex);
            
            // [stack +0]            
        }
        
        int length () { return m_buf.size (); }
        int maxstack () { return 2; } // note: needs to be updated each time emitted code changes
        
        void emit (final EmitCtx ctx)
        {
            // TODO: better error handling here?
            try
            {
                m_buf.writeTo (ctx.m_out);
            }
            catch (IOException ioe)
            {
                if ($assert.ENABLED) $assert.ASSERT (false, ioe.toString ());
            }
        }
        
        
        private final ByteArrayOStream m_buf;
        
        private static final int CLINIT_HEADER_INIT_CAPACITY = 32; // covers about 80% of classes (no reallocation)
        
    } // end of nested class
    
    
    static final class methodHeader extends CodeSegment
    {
        methodHeader (final InstrVisitor visitor, final int localVarIndex)
        {
            super (visitor);
            final ByteArrayOStream buf = new ByteArrayOStream (HEADER_INIT_CAPACITY);
            m_buf = buf;
            
            final ClassDef cls = visitor.m_cls;
            final int coverageFieldrefIndex = visitor.m_coverageFieldrefIndex;
            final int preclinitMethodrefIndex = visitor.m_preclinitMethodrefIndex;

            // TODO: disable this when there are no real blocks following?
            // [in general, use a different template when this method contains a single block]

            // push ref to the static field and dup it:
            buf.write4 (_getstatic,
                        coverageFieldrefIndex >>> 8, // indexbyte1
                        coverageFieldrefIndex,       // indexbyte2
                        _dup);
            
            // [stack +2]
            
            // SF FR 971186: check if it is null and if so run the field
            // init and class RT register code (only relevant for
            // methods that can be executed ahead of <clinit>) [rare] 
            
            buf.write3 (_ifnonnull, // skip over pre-<clinit> method call
                        0,
                        3 + /* size of the block below */ 4);
                        
            // [stack +1]
            
            // block: call pre-<clinit> method
            {
                buf.write4 (_pop,
                            _invokestatic,
                            preclinitMethodrefIndex >>> 8,    // indexbyte1
                            preclinitMethodrefIndex);         // indexbyte2
                            
                // [stack +1]
            }

            // push int literal equal to 'methodID':
            CodeGen.push_int_value (buf, cls, visitor.m_methodID);
            
            // [stack +2]
            
            // push subarray reference:
            buf.write (_aaload);
            
            // [stack +1]
            
            // store it in alias var:
            CodeGen.store_local_object_var (buf, localVarIndex);
            
            // [stack +0]            
        }
        
        int length () { return m_buf.size (); }
        int maxstack () { return 2; } // note: needs to be updated each time emitted code changes
        
        void emit (final EmitCtx ctx)
        {
            // TODO: better error handling here?
            try
            {
                m_buf.writeTo (ctx.m_out);
            }
            catch (IOException ioe)
            {
                if ($assert.ENABLED) $assert.ASSERT (false, ioe.toString ());
            }
        }
        
        
        private final ByteArrayOStream m_buf;
        
        private static final int HEADER_INIT_CAPACITY = 16;
        
    } // end of nested class
    
    
    static final class BlockSegment extends CodeSegment
    {
        public BlockSegment (final InstrVisitor visitor, final int localVarIndex, final int blockID)
        {
            super (visitor);
            final ByteArrayOStream buf = new ByteArrayOStream (BLOCK_INIT_CAPACITY); 
            m_buf = buf;
                        
            final ClassDef cls = visitor.m_cls;
            
            // push alias var:
            CodeGen.load_local_object_var (buf, localVarIndex);
            
            // [stack +1]
            
            // push int value equal to 'blockID':
            CodeGen.push_int_value (buf, cls, blockID);
            
            // [stack +2]
            
            // push boolean 'true':
            buf.write2 (_iconst_1,
            
            // [stack +3]
            
            // store it in the array:
                        _bastore);
            
            // [stack +0]
        }
        
        int length () { return m_buf.size (); }
        int maxstack () { return 3; } // note: needs to be updated each time emitted code changes
        
        void emit (final EmitCtx ctx)
        {
            // TODO: better error handling here?
            try
            {
                m_buf.writeTo (ctx.m_out);
            }
            catch (IOException ioe)
            {
                if ($assert.ENABLED) $assert.ASSERT (false, ioe.toString ());
            }
        }
        
        
        private final ByteArrayOStream m_buf;
        
        private static final int BLOCK_INIT_CAPACITY = 16;
        
    } // end of nested class
    
    
    private static final class LineNumberComparator implements Comparator
    {
        public final int compare (final Object o1, final Object o2)
        {
            return ((LineNumber_info) o1).m_start_pc - ((LineNumber_info) o2).m_start_pc;
        }
        
    } // end of nested class
  
  
  
    private void setClassName (final String fullName)
    {
        if ($assert.ENABLED) $assert.ASSERT (fullName != null && fullName.length () > 0,
            "null or empty input: fullName");
        
        final int lastSlash = fullName.lastIndexOf ('/');
        if (lastSlash < 0)
        {
            m_classPackageName = "";
            m_className = fullName;
        }
        else
        {
            if ($assert.ENABLED) $assert.ASSERT (lastSlash < fullName.length () - 1,
                "malformed class name [" + fullName + "]");
            
            m_classPackageName = fullName.substring (0, lastSlash);
            m_className = fullName.substring (lastSlash + 1);
        }   
    }
    
    private void consumeSignatureData (final int methodID, final int [] basicBlockOffsets)
    {
        // note: by itself, this is not a very good checksum for a class def;
        // however, it is fast to compute and since it will be used along with
        // a class name it should be good at detecting structural changes that
        // matter to us (method and basic block ordering/sizes) 
        
        final int temp1 = basicBlockOffsets.length;
        long temp2 = NBEAST * m_classSignature + (methodID + 1) * temp1;
        
        for (int i = 1; i < temp1; ++ i) // skip the initial 0 offset
        {
            temp2 = NBEAST * temp2 + basicBlockOffsets [i];
        }
        
        m_classSignature = temp2;
    }
     
    // TODO: use a compilation flag to use table assist here instead of binary search
    // BETTER YET: use binsearch for online mode and table assist for offline [when memory is not an issue]
  
    /**
     * Returns the maximum index 'i' such that (values[i] <= x). values[]
     * contains distinct non-negative integers in increasing order. values[0] is 0,
     * 'x' is non-negative.
     * 
     * Edge case:
     *  returns values.length-1 if values [values.length - 1] < x
     */
    private static int lowbound (final int [] values, final int x)
    {
        int low = 0, high = values.length - 1;
        
        // assertion: lb is in [low, high]
        
        while (low <= high)
        {
            final int m = (low + high) >> 1;
            final int v = values [m];
            
            if (v == x)
                return m;
            else if (v < x)
                low = m + 1;
            else // v > x
                high = m - 1;
        }
        
        return high;
    }
    
    private void reset ()
    {
        // TODO: check that all state is reset
        
        m_instrument = false;
        m_metadata = false;
        m_ignoreAlreadyInstrumented = false;
        
        m_cls = null;
        m_classPackageName = null;
        m_className = null;
        m_classSrcFileName = null;
        m_classBlockMetadata = null;
        m_classMethodDescriptors = null;
        
        m_syntheticStringIndex = -1;
        m_coverageFieldrefIndex = -1;
        m_registerMethodrefIndex = -1;
        m_preclinitMethodrefIndex = -1;
        m_classNameConstantIndex = -1;
        m_clinitID = -1;
        m_clinitStatus = 0;
        m_classInstrMethodCount = -1;
        m_classBlockCounts = null;
        m_classSignature = 0;
        
        m_methodID = -1;
        m_methodName = null;
        m_methodFirstLine = 0;
        m_methodBlockOffsets = null;
        m_methodJumpAdjOffsets = null;
        m_methodJumpAdjValues = null;
    }
    
    
    private final boolean m_excludeSyntheticMethods;
    private final boolean m_excludeBridgeMethods;
    private final boolean m_doSUIDCompensation;
    
    private final Logger m_log; // instr visitor logging context is latched at construction time
    
    // non-resettable state:
    
    private boolean m_warningIssued;
    
    
    // resettable state:
    
    private boolean m_instrument;
    private boolean m_metadata;
    private boolean m_ignoreAlreadyInstrumented;
    
    /*private*/ ClassDef m_cls;
    private String m_classPackageName; // in JVM format [com/vladium/...]; empty string for default package
    private String m_className; // in JVM format [<init>, <clinit>, etc], relative to 'm_classPackageName'
    private String m_classSrcFileName;
    private int [][][] m_classBlockMetadata; // methodID->(blockID->line) map [valid only if 'm_constructMetadata' is true; null if the method has not line number table]
    private MethodDescriptor [] m_classMethodDescriptors;
    
    // current class scope: 
    private int m_syntheticStringIndex;     // index of CONSTANT_Utf8 String that reads "Synthetic"     
    /*private*/ int m_coverageFieldrefIndex;    // index of the Fieldref for COVERAGE_FIELD
    private int m_registerMethodrefIndex;   // index of Methodref for RT.r()
    /*private*/ int m_preclinitMethodrefIndex;  // index of Methodref for pre-<clinit> method
    /*private*/ int m_classNameConstantIndex;   // index of CONSTANT_String that is the class name [in JVM format]
    private int m_stampIndex;               // index of CONSTANT_Long that is the class instr stamp
    private int m_clinitID;                 // offset of <clinit> method [-1 if not determined yet]
    private int m_clinitStatus;
    /*private*/ int m_classInstrMethodCount;    // the number of slots in 'm_classBlockCounts' corresponding to methods to be instrumented for coverage
    /*private*/ int [] m_classBlockCounts;      // basic block counts for all methods [only valid just before <clinit> is processed]
    private long m_classSignature;
    
    // current method scope: 
    /*private*/ int m_methodID;                 // offset of current method being instrumented
    private String m_methodName;
    private int m_methodFirstLine;
    private int [] m_methodBlockOffsets;    // [unadjusted] basic block boundaries [length = m_classBlockCounts[m_methodID]+1; the last slot is method bytecode length]
    private int [] m_methodBlockSizes;
    private int [] m_methodJumpAdjOffsets;    // TODO: length ?
    private int [] m_methodJumpAdjValues;        // TODO: length ?
    
    
    private static final long NBEAST = 16661; // prime

    private static final String COVERAGE_FIELD_NAME = "$VR" + "c";
    private static final String SUID_FIELD_NAME = "serialVersionUID";
    private static final String PRECLINIT_METHOD_NAME = "$VR" + "i";

    private static final String JAVA_IO_SERIALIZABLE_NAME = "java/io/Serializable";
    private static final String JAVA_IO_EXTERNALIZABLE_NAME = "java/io/Externalizable";
    
    private static final int EMIT_CTX_MIN_INIT_CAPACITY = 64; // good value determined empirically
    private static final int PRECLINIT_INIT_CAPACITY = 128; // covers about 80% of classes (no reallocation)
    private static final boolean MARK_ADDED_ELEMENTS_SYNTHETIC = true;
    
    /* It appears that nested classes and interfaces ought to be marked
     * as Synthetic; however, neither Sun nor IBM compilers seem to do this.
     * 
     * (As a side note, implied no-arg constructors ought to be marked as
     * synthetic as well, but Sun's javac is not consistent about that either)  
     */
    private static final boolean SKIP_SYNTHETIC_CLASSES = false;
    
    private static final LineNumberComparator LINE_NUMBER_COMPARATOR = new LineNumberComparator ();
    
    private static final byte [] EMPTY_BYTE_ARRAY = new byte [0];

} // end of class
// ----------------------------------------------------------------------------
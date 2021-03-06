package io.smallrye.config;

import static io.smallrye.config.ConfigMappingProvider.skewer;
import static org.objectweb.asm.Type.getDescriptor;
import static org.objectweb.asm.Type.getInternalName;
import static org.objectweb.asm.Type.getType;

import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.microprofile.config.spi.Converter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import io.smallrye.config.ConfigMappingInterface.PrimitiveProperty;

public class ConfigMappingGenerator {
    static final boolean usefulDebugInfo;

    static {
        usefulDebugInfo = Boolean.parseBoolean(AccessController.doPrivileged(
                (PrivilegedAction<String>) () -> System.getProperty("io.smallrye.config.mapper.useful-debug-info")));
    }

    private static final String I_CLASS = getInternalName(Class.class);
    private static final String I_COLLECTIONS = getInternalName(Collections.class);
    private static final String I_CONFIGURATION_OBJECT = getInternalName(ConfigMappingObject.class);
    private static final String I_CONVERTER = getInternalName(Converter.class);
    private static final String I_MAP = getInternalName(Map.class);
    private static final String I_MAPPING_CONTEXT = getInternalName(ConfigMappingContext.class);
    private static final String I_OBJECT = getInternalName(Object.class);
    private static final String I_OPTIONAL = getInternalName(Optional.class);
    private static final String I_RUNTIME_EXCEPTION = getInternalName(RuntimeException.class);
    private static final String I_SMALLRYE_CONFIG = getInternalName(SmallRyeConfig.class);
    private static final String I_STRING_BUILDER = getInternalName(StringBuilder.class);
    private static final String I_STRING = getInternalName(String.class);

    private static final int V_THIS = 0;
    private static final int V_MAPPING_CONTEXT = 1;
    private static final int V_STRING_BUILDER = 2;
    private static final int V_LENGTH = 3;

    static byte[] generate(final ConfigMappingInterface mapping) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = usefulDebugInfo ? new Debugging.ClassVisitorImpl(writer) : writer;

        visitor.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, mapping.getClassInternalName(), null, I_OBJECT,
                new String[] {
                        I_CONFIGURATION_OBJECT,
                        getInternalName(mapping.getInterfaceType())
                });
        visitor.visitSource(null, null);
        MethodVisitor ctor = visitor.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + I_MAPPING_CONTEXT + ";)V", null, null);
        ctor.visitParameter("context", Opcodes.ACC_FINAL);
        Label ctorStart = new Label();
        Label ctorEnd = new Label();
        ctor.visitLabel(ctorStart);
        // stack: -
        ctor.visitVarInsn(Opcodes.ALOAD, V_THIS);
        // stack: this
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, I_OBJECT, "<init>", "()V", false);
        // stack: -
        ctor.visitVarInsn(Opcodes.ALOAD, V_MAPPING_CONTEXT);
        // stack: ctxt
        ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_MAPPING_CONTEXT, "getStringBuilder", "()L" + I_STRING_BUILDER + ';',
                false);
        // stack: sb
        ctor.visitInsn(Opcodes.DUP);
        // stack: sb sb
        Label ctorSbStart = new Label();
        ctor.visitLabel(ctorSbStart);
        ctor.visitVarInsn(Opcodes.ASTORE, V_STRING_BUILDER);
        // stack: sb
        ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_STRING_BUILDER, "length", "()I", false);
        // stack: len
        Label ctorLenStart = new Label();
        ctor.visitLabel(ctorLenStart);
        ctor.visitVarInsn(Opcodes.ISTORE, V_LENGTH);
        // stack: -
        MethodVisitor fio = visitor.visitMethod(Opcodes.ACC_PUBLIC, "fillInOptionals", "(L" + I_MAPPING_CONTEXT + ";)V", null,
                null);
        fio.visitParameter("context", Opcodes.ACC_FINAL);
        Label fioStart = new Label();
        Label fioEnd = new Label();
        fio.visitLabel(fioStart);
        // stack: -
        fio.visitVarInsn(Opcodes.ALOAD, V_MAPPING_CONTEXT);
        // stack: ctxt
        fio.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_MAPPING_CONTEXT, "getStringBuilder", "()L" + I_STRING_BUILDER + ';',
                false);
        // stack: sb
        fio.visitVarInsn(Opcodes.ASTORE, V_STRING_BUILDER);
        // stack: -
        addProperties(visitor, ctor, fio, new HashSet<>(), mapping, mapping.getClassInternalName());
        // stack: -
        fio.visitInsn(Opcodes.RETURN);
        fio.visitLabel(fioEnd);
        fio.visitLocalVariable("mc", 'L' + I_MAPPING_CONTEXT + ';', null, fioStart, fioEnd, V_MAPPING_CONTEXT);
        fio.visitEnd();
        fio.visitMaxs(0, 0);
        // stack: -
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitLabel(ctorEnd);
        ctor.visitLocalVariable("mc", 'L' + I_MAPPING_CONTEXT + ';', null, ctorStart, ctorEnd, V_MAPPING_CONTEXT);
        ctor.visitLocalVariable("sb", 'L' + I_STRING_BUILDER + ';', null, ctorSbStart, ctorEnd, V_STRING_BUILDER);
        ctor.visitLocalVariable("len", "I", null, ctorLenStart, ctorEnd, V_LENGTH);
        ctor.visitEnd();
        ctor.visitMaxs(0, 0);
        visitor.visitEnd();

        return writer.toByteArray();
    }

    private static void addProperties(
            final ClassVisitor cv,
            final MethodVisitor ctor,
            final MethodVisitor fio,
            final Set<String> visited,
            final ConfigMappingInterface mapping,
            final String className) {

        for (ConfigMappingInterface.Property property : mapping.getProperties()) {
            Method method = property.getMethod();
            String memberName = method.getName();
            if (!visited.add(memberName)) {
                // duplicated property
                continue;
            }
            // the field
            String fieldType = getInternalName(method.getReturnType());
            String fieldDesc = getDescriptor(method.getReturnType());
            cv.visitField(Opcodes.ACC_PRIVATE, memberName, fieldDesc, null, null);

            // now process the property
            final ConfigMappingInterface.Property realProperty;
            final boolean optional = property.isOptional();
            if (optional) {
                realProperty = property.asOptional().getNestedProperty();
            } else {
                realProperty = property;
            }

            // now handle each possible type
            if (property.isMap()) {
                // stack: -
                ctor.visitMethodInsn(Opcodes.INVOKESTATIC, I_COLLECTIONS, "emptyMap", "()L" + I_MAP + ';', false);
                // stack: map
                ctor.visitVarInsn(Opcodes.ALOAD, V_THIS);
                // stack: map this
                ctor.visitInsn(Opcodes.SWAP);
                // stack: this map
                ctor.visitFieldInsn(Opcodes.PUTFIELD, className, memberName, fieldDesc);
                // stack: -
                // then sweep it up
                // stack: -
                fio.visitVarInsn(Opcodes.ALOAD, V_MAPPING_CONTEXT);
                // stack: ctxt
                fio.visitLdcInsn(getType(mapping.getInterfaceType()));
                // stack: ctxt iface
                fio.visitLdcInsn(memberName);
                // stack: ctxt iface name
                fio.visitVarInsn(Opcodes.ALOAD, V_THIS);
                // stack: ctxt iface name this
                fio.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_MAPPING_CONTEXT, "getEnclosedField",
                        "(L" + I_CLASS + ";L" + I_STRING + ";L" + I_OBJECT + ";)L" + I_OBJECT + ';', false);
                // stack: obj?
                fio.visitInsn(Opcodes.DUP);
                Label _continue = new Label();
                Label _done = new Label();
                // stack: obj? obj?
                fio.visitJumpInsn(Opcodes.IFNULL, _continue);
                // stack: obj
                fio.visitTypeInsn(Opcodes.CHECKCAST, I_MAP);
                // stack: map
                fio.visitVarInsn(Opcodes.ALOAD, V_THIS);
                // stack: map this
                fio.visitInsn(Opcodes.SWAP);
                // stack: this map
                fio.visitFieldInsn(Opcodes.PUTFIELD, className, memberName, fieldDesc);
                // stack: -
                fio.visitJumpInsn(Opcodes.GOTO, _done);
                fio.visitLabel(_continue);
                // stack: null
                fio.visitInsn(Opcodes.POP);
                // stack: -
                fio.visitLabel(_done);
            } else if (property.isGroup()) {
                // stack: -
                boolean restoreLength = appendPropertyName(ctor, property, memberName);
                // stack: -
                ctor.visitVarInsn(Opcodes.ALOAD, V_MAPPING_CONTEXT);
                // stack: ctxt
                ctor.visitLdcInsn(getType(realProperty.asGroup().getGroupType().getInterfaceType()));
                // stack: ctxt clazz
                ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_MAPPING_CONTEXT, "constructGroup",
                        "(L" + I_CLASS + ";)L" + I_OBJECT + ';', false);
                // stack: nested
                ctor.visitVarInsn(Opcodes.ALOAD, V_THIS);
                // stack: nested this
                ctor.visitInsn(Opcodes.SWAP);
                // stack: this nested
                ctor.visitFieldInsn(Opcodes.PUTFIELD, className, memberName, fieldDesc);
                // stack: -
                if (restoreLength) {
                    restoreLength(ctor);
                }
            } else if (property.isLeaf() || property.isPrimitive() || property.isOptional() && property.isLeaf()) {
                // stack: -
                ctor.visitVarInsn(Opcodes.ALOAD, V_THIS);
                // stack: this
                boolean restoreLength = appendPropertyName(ctor, property, memberName);
                ctor.visitVarInsn(Opcodes.ALOAD, V_MAPPING_CONTEXT);
                ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_MAPPING_CONTEXT, "getConfig", "()L" + I_SMALLRYE_CONFIG + ';',
                        false);
                // stack: this config
                ctor.visitVarInsn(Opcodes.ALOAD, V_STRING_BUILDER);
                ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_STRING_BUILDER, "toString", "()L" + I_STRING + ';', false);
                // stack: this config key
                // get the converter to use
                ctor.visitVarInsn(Opcodes.ALOAD, V_MAPPING_CONTEXT);
                //    public <T> Converter<T> getValueConverter(Class<?> enclosingType, String field) {
                ctor.visitLdcInsn(getType(mapping.getInterfaceType()));
                ctor.visitLdcInsn(memberName);
                ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_MAPPING_CONTEXT, "getValueConverter",
                        "(L" + I_CLASS + ";L" + I_STRING + ";)L" + I_CONVERTER + ';', false);
                // stack: this config key converter
                Label _try = new Label();
                Label _catch = new Label();
                Label _continue = new Label();
                ctor.visitLabel(_try);
                if (property.isOptional()) {
                    ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_SMALLRYE_CONFIG, "getOptionalValue",
                            "(L" + I_STRING + ";L" + I_CONVERTER + ";)L" + I_OPTIONAL + ';', false);
                } else {
                    ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_SMALLRYE_CONFIG, "getValue",
                            "(L" + I_STRING + ";L" + I_CONVERTER + ";)L" + I_OBJECT + ';', false);
                }
                // stack: this value
                if (property.isPrimitive()) {
                    PrimitiveProperty prim = property.asPrimitive();
                    // unbox it
                    // stack: this box
                    String boxType = getInternalName(prim.getBoxType());
                    ctor.visitTypeInsn(Opcodes.CHECKCAST, boxType);
                    ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, boxType, prim.getUnboxMethodName(),
                            prim.getUnboxMethodDescriptor(), false);
                    // stack: this value
                } else if (!property.isOptional()) {
                    assert property.isLeaf();
                    ctor.visitTypeInsn(Opcodes.CHECKCAST, fieldType);
                }
                // stack: this value
                ctor.visitFieldInsn(Opcodes.PUTFIELD, className, memberName, fieldDesc);
                // stack: -
                ctor.visitJumpInsn(Opcodes.GOTO, _continue);
                ctor.visitLabel(_catch);
                // stack: exception
                ctor.visitVarInsn(Opcodes.ALOAD, V_MAPPING_CONTEXT);
                // stack: exception ctxt
                ctor.visitInsn(Opcodes.SWAP);
                // stack: ctxt exception
                ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_MAPPING_CONTEXT, "reportProblem",
                        "(L" + I_RUNTIME_EXCEPTION + ";)V", false);
                // stack: -
                ctor.visitLabel(_continue);
                if (restoreLength) {
                    restoreLength(ctor);
                }
                // add the try/catch
                ctor.visitTryCatchBlock(_try, _catch, _catch, I_RUNTIME_EXCEPTION);
            } else if (property.isOptional()) {
                // stack: -
                ctor.visitMethodInsn(Opcodes.INVOKESTATIC, I_OPTIONAL, "empty", "()L" + I_OPTIONAL + ";", false);
                // stack: empty
                ctor.visitVarInsn(Opcodes.ALOAD, V_THIS);
                // stack: empty this
                ctor.visitInsn(Opcodes.SWAP);
                // stack: this empty
                ctor.visitFieldInsn(Opcodes.PUTFIELD, className, memberName, fieldDesc);

                // also generate a sweep-up stub
                // stack: -
                fio.visitVarInsn(Opcodes.ALOAD, V_MAPPING_CONTEXT);
                // stack: ctxt
                fio.visitLdcInsn(getType(mapping.getInterfaceType()));
                // stack: ctxt iface
                fio.visitLdcInsn(memberName);
                // stack: ctxt iface name
                fio.visitVarInsn(Opcodes.ALOAD, V_THIS);
                // stack: ctxt iface name this
                fio.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_MAPPING_CONTEXT, "getEnclosedField",
                        "(L" + I_CLASS + ";L" + I_STRING + ";L" + I_OBJECT + ";)L" + I_OBJECT + ';', false);
                // stack: obj?
                fio.visitInsn(Opcodes.DUP);
                Label _continue = new Label();
                Label _done = new Label();
                // stack: obj? obj?
                fio.visitJumpInsn(Opcodes.IFNULL, _continue);
                // stack: obj
                fio.visitMethodInsn(Opcodes.INVOKESTATIC, I_OPTIONAL, "of", "(L" + I_OBJECT + ";)L" + I_OPTIONAL + ';', false);
                // stack: opt
                fio.visitVarInsn(Opcodes.ALOAD, V_THIS);
                // stack: opt this
                fio.visitInsn(Opcodes.SWAP);
                // stack: this opt
                fio.visitFieldInsn(Opcodes.PUTFIELD, className, memberName, fieldDesc);
                // stack: -
                fio.visitJumpInsn(Opcodes.GOTO, _done);
                fio.visitLabel(_continue);
                // stack: null
                fio.visitInsn(Opcodes.POP);
                // stack: -
                fio.visitLabel(_done);
            }

            // the accessor method implementation
            MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, memberName, "()" + fieldDesc, null, null);
            // stack: -
            mv.visitVarInsn(Opcodes.ALOAD, V_THIS);
            // stack: this
            mv.visitFieldInsn(Opcodes.GETFIELD, className, memberName, fieldDesc);
            // stack: obj
            if (property.isPrimitive()) {
                mv.visitInsn(getReturnInstruction(property.asPrimitive()));
            } else {
                mv.visitInsn(Opcodes.ARETURN);
            }
            mv.visitEnd();
            mv.visitMaxs(0, 0);
            // end loop
        }
        // subtype overrides supertype
        for (ConfigMappingInterface superType : mapping.getSuperTypes()) {
            addProperties(cv, ctor, fio, visited, superType, className);
        }
    }

    private static boolean appendPropertyName(final MethodVisitor ctor, final ConfigMappingInterface.Property property,
            final String memberName) {
        if (property.isParentPropertyName()) {
            return false;
        }
        // stack: -
        Label _continue = new Label();

        ctor.visitVarInsn(Opcodes.ALOAD, V_STRING_BUILDER);

        ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_STRING_BUILDER, "length", "()I", false);
        // if length != 0 (mean that a prefix exists and not the empty prefix)
        ctor.visitJumpInsn(Opcodes.IFEQ, _continue);

        ctor.visitVarInsn(Opcodes.ALOAD, V_STRING_BUILDER);
        // stack: sb
        ctor.visitLdcInsn('.');
        // stack: sb '.'
        ctor.visitInsn(Opcodes.I2C);
        // stack: sb '.'
        ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_STRING_BUILDER, "append", "(C)L" + I_STRING_BUILDER + ';', false);

        ctor.visitInsn(Opcodes.POP);

        ctor.visitLabel(_continue);

        ctor.visitVarInsn(Opcodes.ALOAD, V_STRING_BUILDER);

        // stack: sb
        if (property.hasPropertyName()) {
            ctor.visitLdcInsn(property.getPropertyName());
        } else {
            ctor.visitLdcInsn(skewer(memberName));
        }
        // stack: sb name
        ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_STRING_BUILDER, "append",
                "(L" + I_STRING + ";)L" + I_STRING_BUILDER + ';', false);
        // stack: sb
        ctor.visitInsn(Opcodes.POP);
        // stack: -
        return true;
    }

    private static void restoreLength(final MethodVisitor ctor) {
        // stack: -
        ctor.visitVarInsn(Opcodes.ALOAD, V_STRING_BUILDER);
        // stack: sb
        ctor.visitVarInsn(Opcodes.ILOAD, V_LENGTH);
        // stack: sb length
        ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, I_STRING_BUILDER, "setLength", "(I)V", false);
        // stack: -
    }

    private static int getReturnInstruction(PrimitiveProperty primitiveProperty) {
        if (primitiveProperty.getPrimitiveType() == float.class) {
            return Opcodes.FRETURN;
        } else if (primitiveProperty.getPrimitiveType() == double.class) {
            return Opcodes.DRETURN;
        } else if (primitiveProperty.getPrimitiveType() == long.class) {
            return Opcodes.LRETURN;
        } else {
            return Opcodes.IRETURN;
        }
    }

    static final class Debugging {
        static StackTraceElement getCaller() {
            return new Throwable().getStackTrace()[2];
        }

        static final class MethodVisitorImpl extends MethodVisitor {

            MethodVisitorImpl(final int api) {
                super(api);
            }

            MethodVisitorImpl(final int api, final MethodVisitor methodVisitor) {
                super(api, methodVisitor);
            }

            public void visitInsn(final int opcode) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitInsn(opcode);
            }

            public void visitIntInsn(final int opcode, final int operand) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitIntInsn(opcode, operand);
            }

            public void visitVarInsn(final int opcode, final int var) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitVarInsn(opcode, var);
            }

            public void visitTypeInsn(final int opcode, final String type) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitTypeInsn(opcode, type);
            }

            public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }

            public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitMethodInsn(opcode, owner, name, descriptor);
            }

            public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor,
                    final boolean isInterface) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            public void visitInvokeDynamicInsn(final String name, final String descriptor, final Handle bootstrapMethodHandle,
                    final Object... bootstrapMethodArguments) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            }

            public void visitJumpInsn(final int opcode, final Label label) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitJumpInsn(opcode, label);
            }

            public void visitLdcInsn(final Object value) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitLdcInsn(value);
            }

            public void visitIincInsn(final int var, final int increment) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitIincInsn(var, increment);
            }

            public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitTableSwitchInsn(min, max, dflt, labels);
            }

            public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitLookupSwitchInsn(dflt, keys, labels);
            }

            public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
                Label l = new Label();
                visitLabel(l);
                visitLineNumber(getCaller().getLineNumber(), l);
                super.visitMultiANewArrayInsn(descriptor, numDimensions);
            }
        }

        static final class ClassVisitorImpl extends ClassVisitor {

            final String sourceFile;

            ClassVisitorImpl(final int api) {
                super(api);
                sourceFile = getCaller().getFileName();
            }

            ClassVisitorImpl(final ClassWriter cw) {
                super(Opcodes.ASM7, cw);
                sourceFile = getCaller().getFileName();
            }

            public void visitSource(final String source, final String debug) {
                super.visitSource(sourceFile, debug);
            }

            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                    final String signature,
                    final String[] exceptions) {
                return new MethodVisitorImpl(api, super.visitMethod(access, name, descriptor, signature, exceptions));
            }
        }
    }
}

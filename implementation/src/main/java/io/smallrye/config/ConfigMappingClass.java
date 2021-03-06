package io.smallrye.config;

import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_INTERFACE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.V1_8;
import static org.objectweb.asm.Type.getDescriptor;
import static org.objectweb.asm.Type.getInternalName;
import static org.objectweb.asm.Type.getMethodDescriptor;
import static org.objectweb.asm.Type.getType;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import io.smallrye.common.constraint.Assert;

final class ConfigMappingClass implements ConfigMappingMetadata {
    private static final ClassValue<ConfigMappingClass> cv = new ClassValue<ConfigMappingClass>() {
        @Override
        protected ConfigMappingClass computeValue(final Class<?> classType) {
            return createConfigurationClass(classType);
        }
    };

    private static final String I_OBJECT = getInternalName(Object.class);
    private static final String I_CLASS = getInternalName(Class.class);
    private static final String I_FIELD = getInternalName(Field.class);

    static ConfigMappingClass getConfigurationClass(Class<?> classType) {
        Assert.checkNotNullParam("classType", classType);
        return cv.get(classType);
    }

    private static ConfigMappingClass createConfigurationClass(final Class<?> classType) {
        if (classType.isInterface() && classType.getTypeParameters().length == 0 ||
                Modifier.isAbstract(classType.getModifiers()) ||
                classType.isEnum()) {
            return null;
        }

        String interfaceName = classType.getPackage().getName() + "." + classType.getSimpleName() +
                classType.getName().hashCode() + "I";

        return new ConfigMappingClass(classType, interfaceName);
    }

    private static byte[] getClassBytes(final Class<?> classType, final String interfaceName) {
        String classInternalName = getInternalName(classType);
        String interfaceInternalName = interfaceName.replace('.', '/');

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, interfaceInternalName, null, I_OBJECT,
                new String[] { getInternalName(ConfigMappingClassMapper.class) });

        Object classInstance;
        try {
            classInstance = classType.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        Field[] declaredFields = classType.getDeclaredFields();
        for (Field declaredField : declaredFields) {
            MethodVisitor mv = writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, declaredField.getName(),
                    getMethodDescriptor(getType(declaredField.getType())), getSignature(declaredField),
                    null);

            boolean hasDefault = false;

            if (declaredField.isAnnotationPresent(WithName.class)) {
                AnnotationVisitor av = mv.visitAnnotation("L" + getInternalName(WithName.class) + ";", true);
                av.visit("value", declaredField.getAnnotation(WithName.class).value());
                av.visitEnd();
            }

            if (declaredField.isAnnotationPresent(WithDefault.class)) {
                AnnotationVisitor av = mv.visitAnnotation("L" + getInternalName(WithDefault.class) + ";", true);
                av.visit("value", declaredField.getAnnotation(WithDefault.class).value());
                av.visitEnd();
                hasDefault = true;
            }

            if (declaredField.isAnnotationPresent(WithConverter.class)) {
                AnnotationVisitor av = mv.visitAnnotation("L" + getInternalName(WithConverter.class) + ";", true);
                av.visit("value", declaredField.getAnnotation(WithConverter.class).value());
                av.visitEnd();
            }

            if (declaredField.isAnnotationPresent(ConfigProperty.class)) {
                ConfigProperty configProperty = declaredField.getAnnotation(ConfigProperty.class);
                {
                    if (!configProperty.name().isEmpty()) {
                        AnnotationVisitor av = mv.visitAnnotation("L" + getInternalName(WithName.class) + ";", true);
                        av.visit("value", configProperty.name());
                        av.visitEnd();
                    }
                }
                {
                    if (!configProperty.defaultValue().equals(ConfigProperty.UNCONFIGURED_VALUE)) {
                        AnnotationVisitor av = mv.visitAnnotation("L" + getInternalName(WithDefault.class) + ";", true);
                        av.visit("value", configProperty.defaultValue());
                        av.visitEnd();
                        hasDefault = true;
                    }
                }
            }

            if (!hasDefault) {
                try {
                    declaredField.setAccessible(true);
                    Object defaultValue = declaredField.get(classInstance);
                    if (hasDefaultValue(declaredField.getType(), defaultValue)) {
                        AnnotationVisitor av = mv.visitAnnotation("L" + getInternalName(WithDefault.class) + ";", true);
                        av.visit("value", defaultValue.toString());
                        av.visitEnd();
                    }
                } catch (IllegalAccessException e) {
                    // Ignore
                }
            }

            mv.visitEnd();
        }

        MethodVisitor ctor = writer.visitMethod(ACC_PUBLIC, "map", "()L" + I_OBJECT + ";", null, null);
        Label ctorStart = new Label();
        ctor.visitLabel(ctorStart);
        ctor.visitTypeInsn(NEW, classInternalName);
        ctor.visitInsn(DUP);
        ctor.visitMethodInsn(INVOKESPECIAL, classInternalName, "<init>", "()V", false);
        ctor.visitVarInsn(ASTORE, 1);

        for (Field declaredField : declaredFields) {
            if (Modifier.isStatic(declaredField.getModifiers()) || Modifier.isVolatile(declaredField.getModifiers())
                    || Modifier.isFinal(declaredField.getModifiers())) {
                continue;
            }

            String name = declaredField.getName();
            Class<?> type = declaredField.getType();

            if (Modifier.isPublic(declaredField.getModifiers())) {
                ctor.visitVarInsn(ALOAD, 1);
                ctor.visitVarInsn(ALOAD, 0);
                ctor.visitMethodInsn(INVOKEINTERFACE, interfaceInternalName, name, getMethodDescriptor(getType(type)),
                        true);
                ctor.visitFieldInsn(PUTFIELD, classInternalName, name, getDescriptor(type));
            } else {
                ctor.visitLdcInsn(getType(classType));
                ctor.visitLdcInsn(name);
                ctor.visitMethodInsn(INVOKEVIRTUAL, I_CLASS, "getDeclaredField",
                        getMethodDescriptor(getType(Field.class), getType(String.class)), false);
                ctor.visitVarInsn(ASTORE, 2);
                ctor.visitVarInsn(ALOAD, 2);
                ctor.visitInsn(ICONST_1);
                ctor.visitMethodInsn(INVOKEVIRTUAL, I_FIELD, "setAccessible", "(Z)V", false);

                ctor.visitVarInsn(ALOAD, 2);
                ctor.visitVarInsn(ALOAD, 1);
                ctor.visitVarInsn(ALOAD, 0);
                ctor.visitMethodInsn(INVOKEINTERFACE, interfaceInternalName, name, getMethodDescriptor(getType(type)), true);

                switch (Type.getType(type).getSort()) {
                    case Type.BOOLEAN:
                        ctor.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                        break;
                    case Type.BYTE:
                        ctor.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                        break;
                    case Type.CHAR:
                        ctor.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                        break;
                    case Type.SHORT:
                        ctor.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                        break;
                    case Type.INT:
                        ctor.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                        break;
                    case Type.FLOAT:
                        ctor.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                        break;
                    case Type.LONG:
                        ctor.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                        break;
                    case Type.DOUBLE:
                        ctor.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                        break;
                }

                ctor.visitMethodInsn(INVOKEVIRTUAL, I_FIELD, "set",
                        getMethodDescriptor(getType(void.class), getType(Object.class), getType(Object.class)), false);
            }
        }

        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitInsn(ARETURN);
        ctor.visitMaxs(2, 2);
        writer.visitEnd();

        return writer.toByteArray();
    }

    private static String getSignature(final Field field) {
        final String typeName = field.getGenericType().getTypeName();
        if (typeName.indexOf('<') != -1 && typeName.indexOf('>') != -1) {
            String signature = "()L" + typeName.replace(".", "/");
            signature = signature.replace("<", "<L");
            signature = signature.replace(">", ";>;");
            return signature;
        }

        return null;
    }

    private static boolean hasDefaultValue(final Class<?> klass, final Object value) {
        if (value == null) {
            return false;
        }

        if (klass.isPrimitive() && value instanceof Number && value.equals(0)) {
            return false;
        }

        if (klass.isPrimitive() && value instanceof Boolean && value.equals(Boolean.FALSE)) {
            return false;
        }

        return !klass.isPrimitive() || !(value instanceof Character) || !value.equals(0);
    }

    private final Class<?> classType;
    private final String interfaceName;

    public ConfigMappingClass(final Class<?> classType, final String interfaceName) {
        this.classType = classType;
        this.interfaceName = interfaceName;
    }

    @Override
    public Class<?> getInterfaceType() {
        return classType;
    }

    @Override
    public String getClassName() {
        return interfaceName;
    }

    @Override
    public byte[] getClassBytes() {
        return ConfigMappingClass.getClassBytes(classType, interfaceName);
    }
}

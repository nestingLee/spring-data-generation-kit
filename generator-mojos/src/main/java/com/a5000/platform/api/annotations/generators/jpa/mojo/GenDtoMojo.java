package com.a5000.platform.api.annotations.generators.jpa.mojo;

import com.a5000.platform.api.annotations.generators.jpa.AbstractGeneratorMojo;
import com.a5000.platform.api.annotations.generators.jpa.utils.Commons;
import com.sun.codemodel.*;
import com.thoughtworks.qdox.model.*;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Copyright 2016 Cyril A. Karpenko <self@nikelin.ru>
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
@Mojo( name = "gen-dto", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = false)
public class GenDtoMojo extends AbstractGeneratorMojo {

    @Parameter( property = "dtoAnnotationClasses")
    private String[] dtoAnnotationClasses = new String[] {};

    @Parameter( property = "dtoInterfaceClasses")
    private String[] dtoInterfaceClasses = new String[] { "java.io.Serializable"};

    @Parameter( property = "generateMethods", defaultValue = "true" )
    private Boolean generateMethods = true;

    @Parameter( property = "annotationInclusionMask", defaultValue = "^javax\\.validation\\..+?")
    private String annotationInclusionMask = "^javax\\.validation\\..+?";

    public GenDtoMojo() {
        super("DTO generator", DTO_GENERATOR_PREFIX, DTO_GENERATOR_SUFFIX, DTO_GENERATOR_POSTFIX);
    }

    @Override
    protected void generateClass(JavaClass entityClazz) throws MojoExecutionException {
        try {
            JDefinedClass dtoClazz = defineClass(entityClazz.getFullyQualifiedName(), dtoPackage,
                    entityClazz.isAbstract());

            if ( entityClazz.getSuperClass() != null && isJpaEntity( entityClazz.getSuperJavaClass() ) ) {
                JClass parentClass = codeModel.ref(
                        prepareClassName(
                            dtoPackage, entityClazz.getSuperJavaClass().getFullyQualifiedName()
                        )
                    );

                List<JavaType> actualTypeArguments = ((JavaParameterizedType)entityClazz.getSuperClass()).getActualTypeArguments();
                if ( actualTypeArguments != null && actualTypeArguments.size() > 0 ) {
                    for ( JavaType narrow : actualTypeArguments ) {
                        parentClass = parentClass.narrow(
                            codeModel.ref(
                                /**
                                 * We assume, that the interfaces used in the domain
                                 * objects definition are shared between the a environments
                                 */
                                isInterface(narrow.getFullyQualifiedName()) ?
                                        prepareClassName( dtoPackage, narrow.getFullyQualifiedName() )
                                        : narrow.getFullyQualifiedName()
                            )
                        );
                    }
                }

                dtoClazz = dtoClazz._extends(parentClass);
            } else {
                if ( dtoInterfaceClasses != null ) {
                    for ( String interfaceClass : dtoInterfaceClasses ) {
                        dtoClazz._implements(codeModel.ref(interfaceClass));
                    }
                }

                if ( dtoAnnotationClasses != null ) {
                    for ( String annotationClass : dtoAnnotationClasses ) {
                        dtoClazz.annotate( codeModel.ref(annotationClass) );
                    }
                }
            }

            if ( entityClazz.getTypeParameters().size() > 0 ) {
                for ( JavaTypeVariable type : entityClazz.getTypeParameters() ) {
                    dtoClazz.generify(
                        type.getName(),
                        codeModel.ref(
                                /**
                                 * We assume, that the interfaces used in the domain
                                 * objects definition are shared between the a environments
                                 */
                            isInterface(type.getValue()) ?
                                prepareClassName(dtoPackage, type.getValue() )
                                : type.getValue()
                        )
                    );
                }
            }

            for ( JavaClass implementedType : entityClazz.getImplementedInterfaces() ) {
                if ( !implementedType.getPackageName().startsWith( basePackage )
                        || hasAnnotation(implementedType, DTO_EXCLUDE_ANNOTATION_CLASS_NAME)  ) {
                    continue;
                }

                dtoClazz._implements(codeModel.ref(implementedType.getFullyQualifiedName()));
            }

            generateClassFields(dtoClazz, entityClazz);
            processClassAnnotations(dtoClazz, entityClazz);
            processClassMethods(dtoClazz, entityClazz);
        } catch (JClassAlreadyExistsException e) {
            throw new MojoExecutionException( e.getMessage(), e );
        }
    }

    protected void processClassMethods( JDefinedClass dtoClazz, JavaClass entityClazz ) {
        if ( !generateMethods ) {
            return;
        }

        for (JavaMethod method : entityClazz.getMethods() ) {
            if ( !hasAnnotation(method, DTO_METHOD_ANNOTATION_CLASS_NAME) ) {
                continue;
            }

            getLog().info("Generating DTO method " + method.getName()
                    + " for DTO " + dtoClazz.fullName() );
            generateDtoMethod(dtoClazz, entityClazz, method);
        }
    }

    protected void generateDtoMethod( JDefinedClass dtoClazz, JavaClass entityClazz, JavaMethod method ) {
        JType returnType = convertType( entityClazz, method.getReturnType() );
        if ( returnType == null ) {
            getLog().error("Inconvertible method " + method.getName() + " return type");
            return;
        }

        boolean skip = false;
        Map<String, JType> parameters = new HashMap<String, JType>();
        for ( JavaParameter parameter : method.getParameters() ) {
            JType parameterType = convertType( entityClazz, parameter.getType() );
            if ( parameterType == null ) {
                getLog().error("Inconvertible method " + method.getName() + " parameter " + parameter.getName() );
                skip = true;
                break;
            }

            parameters.put( parameter.getName(), parameterType );
        }

        if ( skip ) {
            return;
        }

        int flags = 0;
        if ( method.isPublic() ) {
            flags |= JMod.PUBLIC;
        }

        if ( method.isAbstract() ) {
            flags |= JMod.ABSTRACT;
        } else if ( method.isFinal() ) {
            flags |= JMod.FINAL;
        }

        if ( method.isStatic() ) {
            flags |= JMod.STATIC;
        }

        JMethod dtoMethod = dtoClazz.method( flags, returnType, method.getName() );
        for ( Map.Entry<String, JType> parameterEntry : parameters.entrySet() ) {
            dtoMethod.param( parameterEntry.getValue(), parameterEntry.getKey() );
        }

        if ( !method.isAbstract() ) {
            dtoMethod.body().block().directStatement(method.getSourceCode());
        }
    }
    
    protected void processClassAnnotations( JDefinedClass dtoClazz,
                                            JavaClass entityClazz )
        throws MojoExecutionException {
        for ( JavaAnnotation annotation : entityClazz.getAnnotations() ) {
            if ( isA( annotation.getType(),
                    DTO_EXTENDS_ANNOTATION_CLASS_NAME) ) {
                processExtendsAnnotation( dtoClazz, annotation );
            }
        }
    }

    protected void processExtendsAnnotation( JDefinedClass dtoClazz,
                                             JavaAnnotation extendsAnnotation )
        throws MojoExecutionException {
        Object value = extendsAnnotation.getNamedParameter("value");
        if ( value == null ) {
            return;
        }

        if ( value instanceof List) {
            for ( JavaAnnotation parameterAnnotation : (List<JavaAnnotation>) value ) {
                processExtendsParameterAnnotation(dtoClazz, parameterAnnotation);
            }
        } else {
            processExtendsParameterAnnotation( dtoClazz, (JavaAnnotation) value );
        }
    }

    protected void processExtendsParameterAnnotation( JDefinedClass dtoClazz,
                                                      JavaAnnotation annotation )
        throws MojoExecutionException {
        String fieldName = normalizeAnnotationValue(
                (String) annotation.getNamedParameter("value"));

        String fieldType = normalizeAnnotationValue(
                (String) annotation.getNamedParameter("type") ).replace(".class", "");

        JClass fieldTypeClass = codeModel.ref(fieldType);
        if ( annotation.getNamedParameter("isArray") != null
                && annotation.getNamedParameter("isArray").equals("true") ) {
            fieldTypeClass = fieldTypeClass.array();
        }

        List<JClass> typeParameters = new ArrayList<JClass>();
        Object typeParametersValue = annotation.getNamedParameter("typeParameters");
        if ( typeParametersValue != null ) {
            if (typeParametersValue instanceof List) {
                for (String className : (List<String>) typeParametersValue) {
                    typeParameters.add(codeModel.ref( normalizeAnnotationValue(className).replace(".class", "") ));
                }
            } else {
                typeParameters.add( codeModel.ref( normalizeAnnotationValue( (String) typeParametersValue).replace(".class", "") ) );
            }
        }

        _generateClassField( null, dtoClazz, JMod.PRIVATE,
                new ArrayList<JavaAnnotation>(),
                typeParameters.isEmpty() ? fieldTypeClass :
                        fieldTypeClass.narrow(typeParameters), null, fieldName );
    }

    protected void generateClassFields( JDefinedClass dtoClazz,
                                        JavaClass entityClazz )
        throws MojoExecutionException {
        for ( JavaField field : entityClazz.getFields() ) {
            if ( field.isStatic() && skipStaticFields ) {
                continue;
            }

            generateClassField( dtoClazz, entityClazz, field );
        }
    }

    protected void generateClassField( JDefinedClass dtoClazz, JavaClass entityClass,
                                       JavaField field )
        throws MojoExecutionException {
        String fieldName = field.getName();
        String aggregationType = "ID";

        JClass fieldType = codeModel.ref(field.getType().getCanonicalName() );

        JClass realType = null;

        boolean ignoreField = false;
        boolean isComplexType = !isSimpleType( field.getType() );
        boolean isInclude = false;
        for ( JavaAnnotation annotation : field.getAnnotations() ) {
            if ( isA(annotation.getType(), DTO_EXCLUDE_ANNOTATION_CLASS_NAME) ) {
                ignoreField = true;
                break;
            } else if ( isJpaRelationType(annotation.getType()) ) {
                isComplexType = true;
                if ( annotation.getNamedParameter("targetEntity") != null ) {
                    String className = normalizeAnnotationValue(
                            (String)annotation.getNamedParameter("targetEntity") )
                        .replace(".class", "");
                    // Whe have class reference where package part has been reduced by JavaDocBuilder
                    // which happens in a cases when two classes (reference and referent) exists in a
                    // same package
                    if ( !className.contains(".") ) {
                        className = entityClass.getPackageName() + "." + className;
                    }

                    realType = codeModel.ref(
                        prepareClassName(dtoPackage, className)
                    );
                } else {
                    List<JavaType> actualTypeArguments = ((JavaParameterizedType)field.getType()).getActualTypeArguments();
                    if ( actualTypeArguments != null
                            && !actualTypeArguments.isEmpty()
                            && actualTypeArguments.get(0) != null ) {
                        realType = codeModel.ref(
                                prepareClassName(dtoPackage, actualTypeArguments.get(0).getFullyQualifiedName())
                        );
                    }
                }

                if ( isCollectionType( field.getType() ) )  {
                    realType = codeModel.ref(field.getType().getFullyQualifiedName())
                            .narrow( Commons.select(realType, fieldType) );
                }
            } else if ( isA( annotation.getType(), DTO_INCLUDE_ANNOTATION_CLASS_NAME ) ) {
                isInclude = true;
                if ( annotation.getNamedParameter("value") != null ) {
                    aggregationType = normalizeAnnotationValue(
                            (String) annotation.getNamedParameter("value") );
                }
            }
        }

        if ( ignoreField
                || ( isComplexType && !isInclude) ) {
            return;
        }

        if ( isComplexType ) {
            if ( !isCollectionType( fieldType.fullName() ) ) {
                fieldType = codeModel.ref( prepareClassName( dtoPackage, fieldType.fullName() ) );

                if ( aggregationType.equals("AggregationType.ID") ) {
                    fieldName += "Id";
                    realType = codeModel.ref( Long.class );
                }
            } else {
                List<JavaType> actualTypeArguments = ((JavaParameterizedType)field.getType()).getActualTypeArguments();
                if (aggregationType.equals("AggregationType.ENUM")) {
                    fieldType = fieldType.narrow(
                        codeModel.ref((actualTypeArguments.get(0)).getFullyQualifiedName())
                    );
                } else {
                    if ( !aggregationType.equals("AggregationType.ID") ) {
                        fieldType = fieldType.narrow(
                            codeModel.ref(
                                prepareClassName(dtoPackage,
                                    actualTypeArguments.get(0).getFullyQualifiedName()
                                )
                            )
                        );
                    } else {
                        fieldName += "Id";
                        realType = codeModel.ref(List.class).narrow(Long.class);
                    }
                }
            }
        }

        int flags = JMod.PRIVATE;
        if ( field.isTransient() ) {
            flags |= JMod.TRANSIENT;
        }

        if ( field.isStatic() ) {
            flags |= JMod.STATIC;
        }

        String initializeExpression = null;
        if ( field.isFinal() ) {
            flags |= JMod.FINAL;
            initializeExpression = field.getInitializationExpression();
        }

        List<JavaAnnotation> fieldAnnotations = new ArrayList<JavaAnnotation>();
        for ( JavaAnnotation annotation : field.getAnnotations() ) {
            if ( !annotation.getType().getFullyQualifiedName()
                    .matches(annotationInclusionMask) ) {
                continue;
            }

            fieldAnnotations.add( annotation );
        }

        _generateClassField( field, dtoClazz, flags, fieldAnnotations, Commons.select(realType, fieldType), initializeExpression,
                fieldName );
    }

    private void _generateClassField( JavaField originalField, JDefinedClass dtoClazz, int flags,
                                      List<JavaAnnotation> annotations,
                                      JClass type, String initializeExpression,
                                      String fieldName ) throws MojoExecutionException {
        JFieldVar clazzField = dtoClazz.field(flags, type, fieldName );

        if ( type.isInterface() ) {
            if ( isSetType(type.erasure().fullName()) ) {
                clazzField.init( JExpr._new( codeModel.ref(HashSet.class) ) );
            } else if ( isListType(type.erasure().fullName()) ) {
                clazzField.init( JExpr._new( codeModel.ref(ArrayList.class) ) );
            } else if ( isCollectionType(type.erasure().fullName()) ) {
                clazzField.init( JExpr._new( codeModel.ref(HashSet.class) ) );
            }
        } else if ( isCollectionType(type.fullName()) ) {
            clazzField.init( JExpr._new( type ) );
        }

        for ( JavaAnnotation annotation : annotations ) {
            JAnnotationUse annotationUse = clazzField.annotate(
                codeModel.ref(annotation.getType().getFullyQualifiedName())
            );

            transformAnnotation( annotation, annotationUse );
        }

        if ( (flags & JMod.STATIC) == 0 ) {
            generateAccessors(originalField, dtoClazz, clazzField);
        } else if ( initializeExpression != null ) {
            clazzField.init( JExpr.direct(initializeExpression) );
        }
    }

    protected void transformAnnotation( JavaAnnotation annotation,
                                        JAnnotationUse annotationUse )
        throws MojoExecutionException {
        Map<String, Object> annotationParameters = annotation.getNamedParameterMap();
        for ( Map.Entry<String, Object> entry : annotationParameters.entrySet() ) {
            Object value = entry.getValue();
            if ( value instanceof List) {
                JAnnotationArrayMember paramArray = annotationUse.paramArray(entry.getKey());
                for ( Object listItem : (List) value ) {
                    if ( listItem instanceof JavaAnnotation ) {
                        transformAnnotation(
                                (JavaAnnotation) listItem,
                                paramArray.annotate(
                                        codeModel.ref(
                                                ((JavaAnnotation) listItem).getType().getFullyQualifiedName()
                                        )
                                )
                        );
                    }
                }
            } else if ( value instanceof JavaAnnotation ) {
                try {
                    transformAnnotation(
                        (JavaAnnotation) value,
                        annotationUse.annotate(
                            (Class<? extends java.lang.annotation.Annotation>)
                                Thread.currentThread().getContextClassLoader()
                                    .loadClass(
                                        ((JavaAnnotation) value)
                                            .getType().getFullyQualifiedName()
                                    )
                        )
                    );
                } catch (ClassNotFoundException e) {
                    throw new MojoExecutionException("Failed to load annotation class", e);
                }
            } else if ( value instanceof Enum) {
                annotationUse.param( entry.getKey(), (Enum) value );
            } else {
                Method method;
                try {
                    method = annotationUse.getClass().getMethod("param",
                            new Class[] { String.class, entry.getValue().getClass() } );

                    try {
                        method.invoke(annotationUse, entry.getKey(), value );
                    } catch (Throwable e) {
                        throw new MojoExecutionException("Failed to init annotation parameter!",
                                e);
                    }
                } catch (NoSuchMethodException e) {
                    throw new MojoExecutionException("Failed to find annotation param setter!", e );
                }
            }
        }
    }

    @Override
    protected boolean isSupported(JavaClass entityClass) {
        return isJpaEntity(entityClass);
    }
}

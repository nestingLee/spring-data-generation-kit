package com.a5000.platform.api.annotations.generators.jpa.mojo;

import com.a5000.platform.api.annotations.generators.jpa.AbstractGeneratorMojo;
import com.sun.codemodel.*;
import com.thoughtworks.qdox.model.*;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
@Mojo( name = "gen-jpa-converter", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = false)
public class GenJpaToDtoConverterMojo extends AbstractGeneratorMojo {

    private static final String CONVERTER_CLASS_NAME = "DtoConversionService";
    private static final String CONVERTER_METHOD_NAME = "convertToDto";
    private static final String DTO_INCLUDE_ANNOTATION_CLASS_NAME = "DtoInclude";
    private static final String DTO_EXCLUDE_ANNOTATION_CLASS_NAME = "DtoExclude";
    private static final String METHODS_CACHE_FIELD_NAME = "METHODS";
    private static final String CONVERSATION_METHOD_NOT_FOUND_EXCEPTION = "Conversion method not found: %s";
    private static final String LIST_CONVERTER_METHOD_NAME = "convertToDtoList";
    private static final String CONVERT_TO_IDS_LIST_METHOD_NAME = "convertToIdsList";
    private static final String CONVERTER_INVOKE_TYPE_CLASS_NAME = "ConverterInvoke";

    @Parameter( property = "jpaEntityInterface", required = true )
    protected String jpaEntityInterface = "com.a5000.platform.api.model.domain.api.IStoredBean";

    @Parameter( property = "jpaEntityInterface", required = true,
            defaultValue = "com.a5000.platform.api.model.domain.api.IStoredBean")
    protected String transactionalAnnotation;

    @Parameter( property = "profilingEnabled", required = false, defaultValue = "false")
    protected Boolean profilingEnabled = false;

    @Parameter( property = "transactionAnnotationOnConverterMethods",
            required = true, defaultValue = "com.a5000.platform.api.services.A5TransactionalReadOnly")
    protected boolean transactionAnnotationOnConverterMethods;

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private JDefinedClass converterClazz;
    private JFieldVar cacheField;
    private Map<JavaClass, JClass> converterInvokeList = new HashMap();

    public GenJpaToDtoConverterMojo() {
        super("JPA to DTO conversion services generator", "", "", "");
    }

    protected void init() throws JClassAlreadyExistsException {
        if ( !initialized.compareAndSet(false, true) ) {
            return;
        }

        this.converterClazz = codeModel._package(convertersPackage)
                ._class( JMod.PUBLIC, CONVERTER_CLASS_NAME );
        this.converterClazz.annotate( codeModel.ref(SERVICE_ANNOTATION_CLASS_NAME) )
                .param("value", "DtoConversionServiceImpl");
    }
    @Override
    protected void generateClass(JavaClass entityClazz) throws MojoExecutionException {
        if ( entityClazz.isAbstract() ) {
            getLog().info("Skipping abstract class " + entityClazz.getFullyQualifiedName() );
            return;
        }

        generateConverter(entityClazz);
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            init();

            defineCacheField(converterClazz);
            generateTemplateConvertInvokeClass(converterClazz);
            generateConvertToIdsListMethod(converterClazz);
            generateTemplateConvertMethod(converterClazz);
            generateTemplateListConvertMethod(converterClazz);

            super.execute();
        } catch (JClassAlreadyExistsException e) {
            throw new MojoExecutionException( e.getMessage(), e );
        }
    }

    @Override
    protected void onExecutionFinished() {
        defineConverterMethodsCache(converterClazz);
    }

    protected void defineCacheField(JDefinedClass converterClazz) {
        JClass methodType = codeModel.ref(CONVERTER_INVOKE_TYPE_CLASS_NAME);
        this.cacheField = converterClazz.field(JMod.FINAL | JMod.PRIVATE,
                codeModel.ref(Map.class).narrow(codeModel.ref(Class.class), methodType),
                METHODS_CACHE_FIELD_NAME,
                JExpr._new(
                        codeModel.ref(HashMap.class)
                                .narrow(codeModel.ref(Class.class), methodType)
                )
        );
    }

    protected void generateTemplateConvertInvokeClass( JDefinedClass converterClazz )
            throws JClassAlreadyExistsException {
        JDefinedClass converterInvokeClass =
                converterClazz._class(JMod.NONE, "ConverterInvoke", ClassType.INTERFACE);
        converterInvokeClass.generify("T");
        converterInvokeClass.generify("V");

        converterInvokeClass.method(JMod.NONE, codeModel.ref("V"), "convert")
                .param( codeModel.ref("T"), "arg");
    }

    protected void generateConvertToIdsListMethod( JDefinedClass converterClazz ) {
        JMethod converterMethod = converterClazz.method(JMod.PRIVATE | JMod.STATIC,
                codeModel.ref(List.class).narrow(Long.class),
                CONVERT_TO_IDS_LIST_METHOD_NAME );
        JClass entityTypeRef = codeModel.ref(jpaEntityInterface);
        JClass listTypeRef = codeModel.ref(List.class);
        JVar inputArg = converterMethod.param( listTypeRef.narrow( entityTypeRef.wildcard() ), "arg" );
        JBlock block = converterMethod.body();

        JVar resultVar = block.decl( listTypeRef.narrow(Long.class), "result",
                JExpr._new( codeModel.ref(ArrayList.class).narrow(Long.class) ) );
        block._if( JOp.eq( inputArg, JExpr._null() ) )
             ._then()._return(resultVar);

        JBlock foreachBlock = block.forEach( entityTypeRef, "record", inputArg )
             .body();
        foreachBlock._if( JOp.eq( JExpr._null(), JExpr.ref("record") ) )
            ._then()
                ._continue();
        foreachBlock.invoke( resultVar, "add").arg( JExpr.ref("record").invoke("getId") ) ;

        block._return(resultVar);
    }

    protected void generateConverter( JavaClass entityClazz ) throws MojoExecutionException {
        try {
            generateConverterInvokeClass(converterClazz, entityClazz);
        } catch ( JClassAlreadyExistsException e ) {
            throw new MojoExecutionException( e.getMessage(), e );
        }

        JClass entityClazzModel = codeModel.ref( entityClazz.getFullyQualifiedName() );
        JClass dtoRef = codeModel.ref(
            prepareClassName(
                dtoPackage,
                entityClazz.getFullyQualifiedName(),
                DTO_GENERATOR_PREFIX,
                DTO_GENERATOR_SUFFIX,
                DTO_GENERATOR_POSTFIX
            )
        );

        JMethod converterMethod = converterClazz.method(JMod.PUBLIC | JMod.FINAL,
                codeModel.ref(prepareClassName(dtoPackage, entityClazz.getFullyQualifiedName(),
                        DTO_GENERATOR_PREFIX, DTO_GENERATOR_SUFFIX, DTO_GENERATOR_POSTFIX)),
                CONVERTER_METHOD_NAME);

        JVar startedVar = null;
        if ( profilingEnabled ) {
            startedVar = converterMethod.body().decl(codeModel.ref("long"), "started", codeModel.ref("java.lang.System")
                    .staticInvoke("currentTimeMillis"));
            converterMethod.body().add(
                    codeModel.ref("java.lang.System").staticRef("out").invoke("println").arg(
                            entityClazzModel.dotclass().invoke("toString").invoke("concat").arg(" Method Invoked")
                    )
            );
        }

        JVar converterMethodParam = converterMethod.param( entityClazzModel, "value" );
        JBlock block = converterMethod.body();

        block._if( converterMethodParam.eq( JExpr._null() ) )
                ._then()._return( JExpr._null() );

        JVar dtoInstance = block.decl( dtoRef, "result", JExpr._new(dtoRef) );

        Set<CollectedJavaField> collectedFields = collectConvertibleFields(entityClazz);
        for ( CollectedJavaField collectedField : collectedFields ) {
            JExpression valueExpr;
            String fieldName;
            String getterName;
            String setterName;
            JClass resultType;
            if ( collectedField.isConvertible && !collectedField.isSynthetic ) {
                String aggregationType = "AggregationType.ID";
                for ( JavaAnnotation annotation : collectedField.annotations ) {
                    if ( !isA(annotation.getType(), DTO_INCLUDE_ANNOTATION_CLASS_NAME ) ) {
                        continue;
                    }

                    if ( annotation.getNamedParameter("value") != null ) {
                        aggregationType = normalizeAnnotationValue((String) annotation.getNamedParameter("value"));
                    }
                }

                boolean isList = false;
                if ("AggregationType.DTO".equals(aggregationType)) {
                        fieldName = collectedField.name;
                        if (isCollectionType(collectedField.type)) {
                            if (isListType(collectedField.type)) {
                                resultType = codeModel.ref(List.class);
                            } else if (isSetType(collectedField.type)) {
                                resultType = codeModel.ref(Set.class);
                            } else {
                                resultType = codeModel.ref(Collection.class);
                            }

                            resultType = resultType.narrow(
                                    codeModel.ref(
                                        prepareClassName(dtoPackage,
                                                // check
                                            ((JavaParameterizedType)collectedField.type).getActualTypeArguments()
                                                    .get(0).getFullyQualifiedName(),
                                            DTO_GENERATOR_PREFIX, DTO_GENERATOR_SUFFIX, DTO_GENERATOR_POSTFIX)
                                    )
                            );
                        } else {
                            resultType = codeModel.ref(
                                    prepareClassName(dtoPackage, collectedField.type.getFullyQualifiedName(),
                                            DTO_GENERATOR_PREFIX, DTO_GENERATOR_SUFFIX, DTO_GENERATOR_POSTFIX)
                            );
                        }

                        valueExpr = JExpr.cast(
                                resultType,
                                JExpr._this().invoke(CONVERTER_METHOD_NAME).arg(
                                        converterMethodParam.invoke(getterName = generateGetterName(collectedField.name))
                                )
                        );
                } else if ( "AggregationType.ENUM".equals(aggregationType)) {
                        fieldName = collectedField.name;
                        getterName = generateGetterName(collectedField.name);
                        if (isCollectionType(collectedField.type)) {
                            if (isListType(collectedField.type)) {
                                resultType = codeModel.ref(List.class);
                            } else if (isSetType(collectedField.type)) {
                                resultType = codeModel.ref(Set.class);
                            } else {
                                resultType = codeModel.ref(Collection.class);
                            }
                        } else {
                            resultType = codeModel.ref(collectedField.type.getFullyQualifiedName());
                        }
                        valueExpr = JOp.cond(
                                JOp.not(converterMethodParam.invoke(getterName).eq(JExpr._null())),
                                JExpr._new(codeModel.ref(ArrayList.class)).arg(converterMethodParam.invoke(getterName)),
                                JExpr._null());
                } else {
                    fieldName = collectedField.name + "Id";

                    if (classMetaBuilder.getClassByName(collectedField.type.getFullyQualifiedName())
                            .isA(List.class.getCanonicalName()) ) {
                        resultType = codeModel.ref(List.class).narrow(Long.class);
                        isList = true;
                    } else {
                        resultType = codeModel.ref(Long.class);
                    }

                    JInvocation valueAccessInvocation =
                            converterMethodParam.invoke(getterName = generateGetterName(collectedField.name));
                    valueExpr = !isList ?
                        JOp.cond(
                            JOp.not(valueAccessInvocation.eq(JExpr._null())),
                            valueAccessInvocation.invoke(generateGetterName("id")),
                            JExpr._null()
                        ) :
                        JExpr._this().invoke(CONVERT_TO_IDS_LIST_METHOD_NAME).arg( valueAccessInvocation );
                }
            } else  {
                fieldName = collectedField.name;
                resultType = codeModel.ref(collectedField.type.getFullyQualifiedName().replace(".class", ""));

                if ( !collectedField.isSynthetic ) {
                    valueExpr = converterMethodParam.invoke( getterName = generateGetterName(fieldName) );
                } else {
                    JavaAnnotation syntheticFieldAnnotation = collectedField.annotation;

                    boolean isConvertibleCollection = false;
                    if ( syntheticFieldAnnotation != null ) {
                        List<String> typeParameters = new ArrayList<String>();
                        Object syntheticFieldTypeParameters = syntheticFieldAnnotation.getNamedParameter("typeParameters");
                        if (syntheticFieldTypeParameters != null) {
                            if (syntheticFieldTypeParameters instanceof String) {
                                typeParameters.add((String) syntheticFieldTypeParameters);
                            } else {
                                typeParameters.addAll((List<String>) syntheticFieldTypeParameters);
                            }
                        }

                        for (String typeParameterClass : typeParameters) {
                            JClass variableClass = codeModel.ref(typeParameterClass.replace(".class", ""));
                            isConvertibleCollection = variableClass._package().name().startsWith(dtoPackage);
                            resultType = resultType.narrow(variableClass);
                        }
                    }

                    List<String> getters = null;
                    if ( isResolvableSyntheticField(syntheticFieldAnnotation) ) {
                        getters = resolveSyntheticFieldGetter(syntheticFieldAnnotation);
                    } else {
                        getLog().warn("Skipping synthetic field  " + fieldName
                                + " of " + entityClazz.getFullyQualifiedName()
                                + " which is not resolvable...");
                        continue;
                    }

                    valueExpr = converterMethodParam;

                    int i = 0;
                    JExpression npeCheckExpr = null;
                    for ( String methodName : getters ) {
                        valueExpr = valueExpr.invoke(methodName);

                        JExpression valueExprCheck = JOp.not( valueExpr.eq( JExpr._null() ) );

                        if ( i++ != getters.size() - 1 ) {
                            npeCheckExpr = npeCheckExpr == null ? valueExprCheck : JOp.cand(npeCheckExpr, valueExprCheck);
                        }
                    }

                    valueExpr = JExpr.cast(
                        resultType,
                            JOp.cond(
                                    npeCheckExpr,
                                    ((isConvertibleCollection || collectedField.isConvertible) ?
                                            JExpr._this().invoke(CONVERTER_METHOD_NAME).arg(valueExpr)
                                            : valueExpr),
                                    JExpr._null()
                        )
                    );

                    getterName = null;
                }
            }

            setterName = generateSetterName(fieldName);
            if ( !collectedField.isSynthetic
                    && ( !isMethodExists( setterName, dtoRef.fullName() ) ||
                    !isMethodExists(getterName, entityClazz.getFullyQualifiedName()) )  ) {
                getLog().warn("Skipping field " + fieldName
                        + " of " + entityClazz.getFullyQualifiedName()
                        + " with no setter and/or getter '" + getterName + "'...");
                continue;
            }

            JInvocation setterInvocation = dtoInstance.invoke( setterName );
            if ( collectedField.isConvertible ) {
                getLog().info("Generation value setter for convertible field " + collectedField.name + " of type " + collectedField.type.getCanonicalName()
                        + " = isEnum(" + collectedField.type.isEnum() + ") = annotations (" + collectedField.type.getAnnotations()
                        + ") = synthetic(" + collectedField.isSynthetic + ") " +
                        " = convertible( " + collectedField.isConvertible + ") = isArray(" + collectedField.isArray + ")" +
                        " = parent ( " + collectedField.type.getFullyQualifiedName() + ")");
                JVar convertedValueVar = block.decl( resultType, fieldName + "Converted" )
                        .init( valueExpr );

                block._if( JOp.not( convertedValueVar.eq(JExpr._null()) ) )
                        ._then().add( setterInvocation.arg(convertedValueVar) );
            } else {
                getLog().info("Generation value setter for non-convertible field " + collectedField.name + " of type " + collectedField.type.getCanonicalName()
                        + " = isEnum(" + collectedField.type.isEnum() + ") = annotations (" + collectedField.type.getAnnotations()
                        + ") = synthetic(" + collectedField.isSynthetic + ") " +
                        " = convertible( " + collectedField.isConvertible + ") = isArray(" + collectedField.isArray + ")" +
                        " = parent ( " + collectedField.type.getSuperClass().getFullyQualifiedName() + ")");
                block.add( setterInvocation.arg( valueExpr ) );
            }
        }

        if ( profilingEnabled ) {
            converterMethod.body().add(
                    codeModel.ref("java.lang.System").staticRef("out").invoke("println").arg(
                            codeModel.ref("java.lang.String").staticInvoke("valueOf").arg(
                                    codeModel.ref("java.lang.System").staticInvoke("currentTimeMillis").minus(startedVar)
                            ).invoke("concat").arg(" ms")
                    )
            );
        }

        block._return(dtoInstance);
    }

    private void generateConverterInvokeClass(JDefinedClass converterClazz, JavaClass entityClazz) throws JClassAlreadyExistsException {
        JClass dtoClassType = codeModel.ref(prepareClassName(dtoPackage, entityClazz.getFullyQualifiedName(),
                DTO_GENERATOR_PREFIX, DTO_GENERATOR_SUFFIX, DTO_GENERATOR_POSTFIX));
        JClass originalType = codeModel.ref( entityClazz.getFullyQualifiedName() );
        JDefinedClass converterInvokeClass =
                converterClazz._class(JMod.PRIVATE | JMod.FINAL, entityClazz.getName() + "ConverterInvoke", ClassType.CLASS)
                ._implements(codeModel.ref(CONVERTER_INVOKE_TYPE_CLASS_NAME)
                        .narrow(originalType)
                        .narrow(dtoClassType));

        JMethod method = converterInvokeClass.method(JMod.PUBLIC, dtoClassType,"convert");
        JVar arg = method.param( codeModel.ref(entityClazz.getFullyQualifiedName()), "arg");
        method.body()
            ._return(
                JExpr.invoke( CONVERTER_METHOD_NAME ).arg(JExpr.cast(originalType, arg))
            );

        converterInvokeList.put( entityClazz, converterInvokeClass);
    }

    /**
     * @param converterClazz
     */
    protected void generateTemplateListConvertMethod( JDefinedClass converterClazz ) {
        JMethod method = converterClazz.method(
            JMod.PUBLIC,
            codeModel.ref(List.class).narrow( codeModel.ref("T") ),
            LIST_CONVERTER_METHOD_NAME
        );

        if ( transactionAnnotationOnConverterMethods ) {
            method.annotate(codeModel.ref(transactionalAnnotation));
        }

        JTypeVar returnType = method.generify("T");
        JTypeVar methodParamType = method.generify("V");

        JVar methodParam =
                method.param( codeModel.ref(Collection.class).narrow(methodParamType),
                        "records" );

        JBlock methodBody = method.body();
        methodBody._if( methodParam.eq( JExpr._null() ) )
                ._then()
                    ._throw(
                        JExpr._new( codeModel.ref( IllegalStateException.class ) )
                        .arg("<null>") );

        JVar result = methodBody.decl( codeModel.ref(List.class), "result")
                .init( JExpr._new( codeModel.ref(ArrayList.class) ) );
        JForEach recordsIterator = methodBody.forEach(
                codeModel.ref(Object.class), "record", methodParam );
        recordsIterator.body()
                ._if( recordsIterator.var().eq( JExpr._null() ) )
                    ._then()
                        ._continue();
        recordsIterator.body()
            .invoke( result, "add" )
                .arg(
                    JExpr._this()
                         .invoke(CONVERTER_METHOD_NAME)
                         .arg( recordsIterator.var() )
                );

        methodBody._return(
            JExpr.cast(
                codeModel.ref(List.class).narrow(returnType),
                result
            )
        );
    }

    protected void generateTemplateConvertMethod( JDefinedClass converterClazz ) {
        JMethod method = converterClazz.method(JMod.PUBLIC, codeModel.ref("T"), "convertToDto");
        JTypeVar typeVar = method.generify("T");

        JVar methodParam = method.param( codeModel.ref(Object.class), "value" );

        JVar startedVar = null;
        if ( profilingEnabled ) {
            startedVar = method.body().decl(codeModel.ref("long"), "started", codeModel.ref("java.lang.System")
                    .staticInvoke("currentTimeMillis"));
            method.body().add(
                    codeModel.ref("java.lang.System").staticRef("out").invoke("println").arg(
                            methodParam.invoke("getClass").invoke("toString").invoke("concat").arg(" Method Invoked")
                    )
            );
        }

        method.body()._if( methodParam.eq( JExpr._null() ) )
            ._then()
                ._return(JExpr._null());

        JType collectionType = codeModel.ref(Collection.class);

        method.body()._if( methodParam._instanceof( collectionType ) )
            ._then()
                ._return(
                    JExpr.cast( typeVar,
                        JExpr._this().invoke("convertToDtoList")
                            .arg(JExpr.cast(collectionType, methodParam)) )
                );

        JVar methodDeclaration = method.body().decl(
                codeModel.ref(CONVERTER_INVOKE_TYPE_CLASS_NAME),
                "converter",
                cacheField.invoke("get").arg( methodParam.invoke("getClass") ) );
        method.body()._if(
            methodDeclaration.eq( JExpr._null() ) )
                ._then()._throw(
                    JExpr._new( codeModel.ref(IllegalStateException.class) )
                        .arg( String.format(CONVERSATION_METHOD_NOT_FOUND_EXCEPTION,
                                methodParam.invoke("getClass").invoke("getCanonicalName") ) )
                );

        JTryBlock convertBlock = method.body()._try();
        JVar resultVar = convertBlock.body().decl(typeVar, "result",
            JExpr.cast(typeVar, methodDeclaration.invoke("convert")
                    .arg(methodParam))
        );

        if ( profilingEnabled ) {
            convertBlock.body().add(
                    codeModel.ref("java.lang.System").staticRef("out").invoke("println").arg(
                            codeModel.ref("java.lang.String").staticInvoke("valueOf").arg(
                                    codeModel.ref("java.lang.System").staticInvoke("currentTimeMillis").minus(startedVar)
                            ).invoke("concat").arg(" ms")
                    )
            );
        }

        convertBlock.body()._return( resultVar );

        JCatchBlock covertBlockCatch = convertBlock._catch( codeModel.ref(Exception.class) );
        JVar param = covertBlockCatch.param("e");
        covertBlockCatch.body()
            ._throw(
                JExpr._new( codeModel.ref(IllegalStateException.class) )
                    .arg( param.invoke("getMessage") )
                    .arg( param )
            );
    }

    protected void defineConverterMethodsCache( JDefinedClass converterClazz ) {
        JBlock block = converterClazz.constructor(JMod.PUBLIC).body();
        for ( Map.Entry<JavaClass, JClass> pair : converterInvokeList.entrySet() ) {
            block.add(
                    cacheField.invoke("put").arg(codeModel.ref(pair.getKey().getFullyQualifiedName()).dotclass())
                            .arg(JExpr._new(pair.getValue()))
            );
        }
    }

    protected boolean isConvertibleField( JavaClass type ) {
        return !this.isSimpleType(type) && !type.isEnum() && !hasAnnotation(type, DTO_EXCLUDE_ANNOTATION_CLASS_NAME);
    }

    @Override
    protected boolean isSupported(JavaClass entityClass) {
        return isJpaEntity(entityClass);
    }

    protected Set<CollectedJavaField> collectConvertibleFields(JavaClass javaClass) {
        Set<JavaField> fields = super.collectAllFields(javaClass);
        Set<CollectedJavaField> result = new HashSet<CollectedJavaField>();
        for ( JavaField field : fields ) {
            if ( field.isStatic() && skipStaticFields ) {
                continue;
            }

            boolean isConvertible = isConvertibleField(field.getType());
            if ( !isConvertible && !isSimpleType(field.getType()) ) {
                continue;
            }

            result.add( new CollectedJavaField(null, field.getType(), field.getName(), field.getType().isArray(),
                    isConvertible, false,
                    field.getAnnotations().toArray(new JavaAnnotation[field.getAnnotations().size()])) );
        }

        result.addAll( collectSyntheticFields(javaClass) );

        return result;
    }

    protected Collection<CollectedJavaField> collectSyntheticFields( JavaClass javaClass ) {
        Collection<CollectedJavaField> result = new HashSet<CollectedJavaField>();

        JavaClass parent = javaClass;
        while ( parent != null ) {
            for ( JavaAnnotation annotation : parent.getAnnotations() ) {
                if ( !isA(annotation.getType(),
                        DTO_EXTENDS_ANNOTATION_CLASS_NAME) ) {
                    continue;
                }

                Object value = annotation.getNamedParameter("value");
                if ( value instanceof List ) {
                    for ( JavaAnnotation paramAnnotation : (List<JavaAnnotation>) value ) {
                        result.add( collectSyntheticField(javaClass, paramAnnotation) );
                    }
                } else {
                    result.add(collectSyntheticField(javaClass, value));
                }
            }

            parent = parent.getSuperJavaClass();
        }

        return result;
    }

    protected CollectedJavaField collectSyntheticField(JavaClass javaClass, Object value) {
        JavaClass fieldType = classMetaBuilder.getClassByName(
            normalizeAnnotationValue(
                (String) ((JavaAnnotation) value).getNamedParameter("type")
            )
        );
        String fieldName = normalizeAnnotationValue(
            (String) ((JavaAnnotation) value).getNamedParameter("value")
        );
        boolean isEnum = Boolean.valueOf(normalizeAnnotationValue(
                (String) ((JavaAnnotation) value).getNamedParameter("isEnum")
        ));

        return new CollectedJavaField((JavaAnnotation)value,
            fieldType, fieldName,
            ((JavaAnnotation) value).getNamedParameter("isArray") != null
                    && ((JavaAnnotation) value).getNamedParameter("isArray").equals("true"),
            !isEnum && isConvertibleField(fieldType),
            true,
            new JavaAnnotation[] {});
    }

    class CollectedJavaField {
        final boolean isSynthetic;
        final boolean isConvertible;
        final boolean isArray;
        final String name;
        final JavaClass type;
        final JavaAnnotation annotation;
        final JavaAnnotation[] annotations;

        public CollectedJavaField(JavaAnnotation annotation,
                                  JavaClass type, String name,
                                  boolean isArray,
                                  boolean isConvertible,
                                  boolean isSynthetic,
                                  JavaAnnotation[] annotations) {
            this.annotation = annotation;
            this.type = type;
            this.name = name;
            this.isArray = isArray;
            this.isConvertible = isConvertible;
            this.isSynthetic = isSynthetic;
            this.annotations = annotations;
        }
    }
}

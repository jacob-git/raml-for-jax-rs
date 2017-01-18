package org.raml.jaxrs.generator.builders.resources;

import com.google.common.base.Function;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import org.raml.jaxrs.generator.CurrentBuild;
import org.raml.jaxrs.generator.GenerationException;
import org.raml.jaxrs.generator.HTTPMethods;
import org.raml.jaxrs.generator.Names;
import org.raml.jaxrs.generator.ResourceUtils;
import org.raml.jaxrs.generator.builders.CodeContainer;
import org.raml.jaxrs.generator.builders.JavaPoetTypeGeneratorBase;
import org.raml.jaxrs.generator.builders.extensions.ContextImpl;
import org.raml.jaxrs.generator.ramltypes.GMethod;
import org.raml.jaxrs.generator.ramltypes.GParameter;
import org.raml.jaxrs.generator.ramltypes.GRequest;
import org.raml.jaxrs.generator.ramltypes.GResource;
import org.raml.jaxrs.generator.ramltypes.GResponse;
import org.raml.jaxrs.generator.ramltypes.GResponseType;
import org.raml.jaxrs.generator.ramltypes.GType;
import org.raml.jaxrs.generator.v10.Annotations;
import org.raml.jaxrs.generator.v10.TypeUtils;
import org.raml.jaxrs.generator.v10.V10GMethod;
import org.raml.jaxrs.generator.v10.V10GResource;
import org.raml.jaxrs.generator.v10.V10GResponse;

import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;
import javax.ws.rs.Consumes;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Created by Jean-Philippe Belanger on 10/27/16.
 * Abstraction of creation.
 */
public class ResourceBuilder implements ResourceGenerator {

    private final CurrentBuild build;
    private final GResource topResource;
    private final String name;
    private final String uri;

    public ResourceBuilder(CurrentBuild build, GResource resource, String name, String uri) {

        this.build = build;
        this.topResource = resource;
        this.name = name;
        this.uri = uri;
    }

    @Override
    public void output(CodeContainer<TypeSpec> container) throws IOException {


        TypeSpec.Builder typeSpec = TypeSpec.interfaceBuilder(Names.typeName(name))
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(Path.class)
                        .addMember("value", "$S", uri).build());

        if ( topResource instanceof V10GResource ) {

            typeSpec = Annotations.ON_RESOURCE_CLASS_CREATION.get(build.getApi(), ((V10GResource) topResource).implementation()).onResource(new ContextImpl(build),
                    (V10GResource) topResource, typeSpec);
        }

        buildResource(typeSpec, topResource);

        recurse(typeSpec, topResource);

        if ( topResource instanceof V10GResource ) {

            typeSpec = Annotations.ON_RESOURCE_CLASS_FINISH.get(build.getApi(), ((V10GResource) topResource).implementation()).onResource(new ContextImpl(build),
                    (V10GResource) topResource, typeSpec);
        }

        container.into(typeSpec.build());
    }

    private void recurse(TypeSpec.Builder typeSpec, GResource parentResource) {

        for (GResource resource : parentResource.resources()) {

            buildResource(typeSpec, resource);
            recurse(typeSpec, resource);
        }
    }

    private void buildResource(TypeSpec.Builder typeSpec, GResource currentResource) {

        Multimap<GMethod, GRequest> incomingBodies = ArrayListMultimap.create();
        Multimap<GMethod, GResponse> responses = ArrayListMultimap.create();
        ResourceUtils.fillInBodiesAndResponses(currentResource, incomingBodies, responses);

        Map<String, TypeSpec.Builder> responseSpecs = createResponseClass(typeSpec, incomingBodies, responses);

        for (GMethod gMethod : currentResource.methods()) {

            String methodName = Names.resourceMethodName(gMethod.resource(), gMethod);
            Set<String> mediaTypesForMethod = fetchAllMediaTypesForMethodResponses(gMethod);
            if ( gMethod.body().size() == 0) {

                createMethodWithoutBody(typeSpec, gMethod, mediaTypesForMethod, methodName, responseSpecs);
            } else {
                Multimap<String, String> ramlTypeToMediaType = accumulateMediaTypesPerType(incomingBodies, gMethod);
                for (GRequest gRequest : gMethod.body()) {

                   if (ramlTypeToMediaType.containsKey(gRequest.type().name())) {
                       createMethodWithBody(typeSpec, gMethod, ramlTypeToMediaType, methodName, gRequest, responseSpecs);
                       ramlTypeToMediaType.removeAll(gRequest.type().name());
                   }
                }
            }
        }
    }

    private Multimap<String, String> accumulateMediaTypesPerType(Multimap<GMethod, GRequest> incomingBodies,
            GMethod gMethod) {
        Multimap<String, String> ramlTypeToMediaType = ArrayListMultimap.create();
        for (GRequest request : incomingBodies.get(gMethod)) {
            if ( request != null ) {
                ramlTypeToMediaType.put(request.type().name(), request.mediaType());
            }
        }
        return ramlTypeToMediaType;
    }

    private void createMethodWithoutBody(TypeSpec.Builder typeSpec, GMethod gMethod, Set<String> mediaTypesForMethod,
            String methodName, Map<String, TypeSpec.Builder> responseSpecs) {

        MethodSpec.Builder methodSpec = createMethodBuilder(gMethod, methodName, mediaTypesForMethod, responseSpecs);

        // here I would run my  plugins....

        if ( gMethod instanceof V10GMethod ) {

            methodSpec = Annotations.ON_METHOD_FINISH.get(build.getApi(), ((V10GMethod) gMethod).implementation()).onMethod(new ContextImpl(build), (V10GMethod) gMethod, methodSpec);
        }

        typeSpec.addMethod(methodSpec.build());
    }

    private void createMethodWithBody(TypeSpec.Builder typeSpec, GMethod gMethod,
            Multimap<String, String> ramlTypeToMediaType, String methodName, GRequest gRequest, Map<String, TypeSpec.Builder> responseSpec) {

        MethodSpec.Builder methodSpec = createMethodBuilder(gMethod, methodName, new HashSet<String>(), responseSpec);
        TypeName name = gRequest.type().defaultJavaTypeName(build.getModelPackage());
        methodSpec.addParameter(ParameterSpec.builder(name, "entity").build());
        handleMethodConsumer(methodSpec, ramlTypeToMediaType, gRequest.type());

        if ( gMethod instanceof V10GMethod ) {

            methodSpec = Annotations.ON_METHOD_FINISH.get(build.getApi(), ((V10GMethod) gMethod).implementation()).onMethod(new ContextImpl(build), (V10GMethod) gMethod, methodSpec);
        }

        typeSpec.addMethod(methodSpec.build());
    }

    private Set<String> fetchAllMediaTypesForMethodResponses(GMethod gMethod) {

        Set<String> mediaTypes = new HashSet<>();
        for (GResponse gResponse : gMethod.responses()) {

            mediaTypes.addAll(Lists.transform(gResponse.body(), new Function<GResponseType, String>() {
                @Nullable
                @Override
                public String apply(@Nullable GResponseType input) {
                    return input.mediaType();
                }
            }));
        }

        return mediaTypes;
    }

    private Map<String, TypeSpec.Builder>  createResponseClass(TypeSpec.Builder typeSpec, Multimap<GMethod, GRequest> bodies, Multimap<GMethod, GResponse> responses) {

        Map<String, TypeSpec.Builder> map = new HashMap<>();

        Set<GMethod> allMethods = new HashSet<>();
        allMethods.addAll(bodies.keySet());
        allMethods.addAll(responses.keySet());
        for (GMethod gMethod : allMethods) {

            if ( gMethod.responses().size() == 0 ) {

                continue;
            }

            String defaultName = Names.responseClassName(gMethod.resource(), gMethod);
            TypeSpec.Builder responseClass = TypeSpec
                    .classBuilder(defaultName)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .superclass(ClassName.get(build.getSupportPackage(), "ResponseDelegate"))
                    .addMethod(
                            MethodSpec.constructorBuilder()
                                    .addParameter(javax.ws.rs.core.Response.class, "Response")
                                    .addModifiers(Modifier.PRIVATE)
                                    .addCode("super(Response);\n").build()
                    );

            if ( gMethod instanceof V10GMethod ) {

                responseClass = Annotations.ON_RESPONSE_CLASS_CREATION.get(build.getApi(), ((V10GMethod) gMethod).implementation()).onMethod(new ContextImpl(build),
                        (V10GMethod) gMethod, responseClass);
            }

            if ( responseClass == null ) {

                map.put(defaultName, null);
                continue;
            }

            TypeSpec currentClass = responseClass.build();
            for (GResponse gResponse : responses.get(gMethod)) {

                if ( gResponse == null ) {
                    continue;
                }
                if(gResponse.body().size() == 0 ) {
                    String httpCode = gResponse.code();
                    MethodSpec.Builder builder = MethodSpec.methodBuilder("respond" + httpCode);
                    builder
                            .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
                            .addStatement("Response.ResponseBuilder responseBuilder = Response.status(" + httpCode + ")")
                            .addStatement("return new $N(responseBuilder.build())", currentClass)
                            .returns(TypeVariableName.get(currentClass.name))
                            .build();

                    responseClass.addMethod(builder.build());
                } else {
                    for (GResponseType typeDeclaration : gResponse.body()) {

                        String httpCode = gResponse.code();
                        MethodSpec.Builder builder = MethodSpec.methodBuilder( Names.methodName("respond", httpCode,  "With", typeDeclaration.mediaType() ) )
                                                        .addModifiers(Modifier.STATIC, Modifier.PUBLIC);

                        if ( gResponse instanceof V10GResponse ) {

                            builder = Annotations.ON_RESPONSE_METHOD_CREATION.get((V10GResponse) gResponse).onMethod(new ContextImpl(build),
                                    (V10GResponse) gResponse, builder);
                        }

                        builder
                                .addStatement("Response.ResponseBuilder responseBuilder = Response.status(" + httpCode + ").header(\"Content-Type\", \""
                                        + typeDeclaration.mediaType() + "\")")
                                .addStatement("responseBuilder.entity(entity)")
                                .addStatement("return new $N(responseBuilder.build())", currentClass)
                                .returns(TypeVariableName.get(currentClass.name))
                                .build();
                        TypeName typeName = typeDeclaration.type().defaultJavaTypeName(build.getModelPackage());
                        if (typeName == null) {
                            throw new GenerationException(typeDeclaration + " was not seen before");
                        }

                        builder.addParameter(ParameterSpec.builder(typeName, "entity").build());

                        if ( gResponse instanceof V10GResponse ) {

                            builder = Annotations.ON_RESPONSE_METHOD_FINISH.get(build.getApi(), ((V10GResponse) gResponse).implementation()).onMethod(new ContextImpl(build),
                                    (V10GResponse) gResponse, builder);
                        }

                        responseClass.addMethod(builder.build());
                    }
                }
            }

            if ( gMethod instanceof V10GMethod ) {

                responseClass = Annotations.ON_RESPONSE_CLASS_FINISH.get(build.getApi(), ((V10GMethod) gMethod).implementation()).onMethod(new ContextImpl(build),
                        (V10GMethod) gMethod, responseClass);
            }
            map.put(defaultName, responseClass);
            typeSpec.addType(responseClass.build());
        }

        return map;
    }

    private MethodSpec.Builder createMethodBuilder(GMethod gMethod, String methodName, Set<String> mediaTypesForMethod,
            Map<String, TypeSpec.Builder> responseSpec) {

        MethodSpec.Builder methodSpec = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC);
        if ( gMethod instanceof V10GMethod ) {

            methodSpec = Annotations.ON_METHOD_CREATION.get(build.getApi(), ((V10GMethod) gMethod).implementation()).onMethod(new ContextImpl(build), (V10GMethod) gMethod, methodSpec);
        }

        for (GParameter typeDeclaration : gMethod.resource().uriParameters()) {

            if (TypeUtils.isComposite(typeDeclaration)) {
                throw new GenerationException("uri parameter is composite: " + typeDeclaration);
            }

            methodSpec.addParameter(
                    ParameterSpec.builder(
                            typeDeclaration.type().defaultJavaTypeName(build.getModelPackage()), Names.methodName(typeDeclaration.name()))
                            .addAnnotation(
                                    AnnotationSpec.builder(PathParam.class).addMember("value", "$S", typeDeclaration.name())
                                            .build())
                            .build());

        }
        for (GParameter typeDeclaration : gMethod.queryParameters()) {
            if (TypeUtils.isComposite(typeDeclaration)) {
                throw new GenerationException("query parameter is composite: " + typeDeclaration);
            }

            methodSpec.addParameter(
                    ParameterSpec.builder(
                            typeDeclaration.type().defaultJavaTypeName(build.getModelPackage()), Names.methodName(typeDeclaration.name()))
                            .addAnnotation(
                                    AnnotationSpec.builder(QueryParam.class).addMember("value", "$S", typeDeclaration.name())
                                            .build())
                            .build());
        }

        buildNewWebMethod(gMethod, methodSpec);


        if ( gMethod.resource().parentResource() != null ) {

            methodSpec.addAnnotation(AnnotationSpec.builder(Path.class).addMember("value", "$S", gMethod.resource().resourcePath()).build());
        }

        if ( gMethod.responses().size() != 0 ) {
            TypeSpec.Builder responseSpecForMethod = responseSpec.get(Names.responseClassName(gMethod.resource(), gMethod));
            if ( responseSpecForMethod == null ) {

                methodSpec.returns(ClassName.get(Response.class));
            } else {
                methodSpec.returns(ClassName.get("", responseSpecForMethod.build().name));
            }
        } else {
            methodSpec.returns(ClassName.VOID);
        }

        if ( mediaTypesForMethod.size() > 0 ) {
            AnnotationSpec.Builder ann = buildAnnotation(mediaTypesForMethod, Produces.class);
            methodSpec.addAnnotation(ann.build());
        }
        return methodSpec;
    }

    private void buildNewWebMethod(GMethod gMethod, MethodSpec.Builder methodSpec) {
        Class<? extends Annotation> type = HTTPMethods.methodNameToAnnotation(gMethod.method());
        if ( type == null ) {

            String name = gMethod.method().toUpperCase();
            final ClassName className = ClassName.get(build.getSupportPackage(), name);
            final TypeSpec.Builder builder = TypeSpec.annotationBuilder(className);
            builder
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(AnnotationSpec.builder(Target.class)
                       .addMember("value", "{$T.$L}", ElementType.class, "METHOD").build())
                    .addAnnotation(AnnotationSpec.builder(Retention.class).addMember("value", "$T.$L", RetentionPolicy.class, "RUNTIME").build())
                    .addAnnotation(AnnotationSpec.builder(HttpMethod.class).addMember("value", "$S", name).build());
            build.newSupportGenerator(new JavaPoetTypeGeneratorBase(className) {

                @Override
                public void output(CodeContainer<TypeSpec.Builder> rootDirectory) throws IOException {

                    rootDirectory.into(builder);
                }
            });

            methodSpec
                    .addAnnotation(AnnotationSpec.builder(className).build());

        } else {

            methodSpec
                    .addAnnotation(AnnotationSpec.builder(type).build());
        }
    }

    private void handleMethodConsumer(MethodSpec.Builder methodSpec,
            Multimap<String, String> ramlTypeToMediaType,
            GType typeDeclaration) {

        Collection<String> mediaTypes = ramlTypeToMediaType.get(typeDeclaration.type());

        AnnotationSpec.Builder ann = buildAnnotation(mediaTypes, Consumes.class);
        methodSpec.addAnnotation(ann.build());
    }

    private AnnotationSpec.Builder buildAnnotation(Collection<String> mediaTypes, Class<? extends Annotation> type) {
        AnnotationSpec.Builder ann = AnnotationSpec.builder(type);
        for (String mediaType : mediaTypes) {

            ann.addMember("value", "$S", mediaType);
        }
        return ann;
    }
}
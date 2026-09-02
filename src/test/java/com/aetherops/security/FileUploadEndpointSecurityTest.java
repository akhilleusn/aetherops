package com.aetherops.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security audit item 10 (file upload / input validation): this app has no runbook-attachment,
 * incident-screenshot, or any other file-upload endpoint — there is no {@code MultipartFile}
 * parameter anywhere in {@code com.aetherops}, no {@code spring.servlet.multipart.*}
 * configuration, and no controller accepts {@code multipart/form-data}. So there is nothing
 * for a "malicious .exe / oversized file" attack to land on today.
 *
 * <p>This test doesn't just assert that in prose — it scans every {@code @RestController} in
 * the app for a {@link MultipartFile} (or array/collection of one) parameter and fails loudly
 * if it ever finds one. That turns "we checked once" into a trip wire: the day someone adds a
 * real upload endpoint, this test fails and points them at the missing MIME-type/size
 * validation this class's Javadoc calls out, rather than the gap going unnoticed until it
 * ships.
 */
class FileUploadEndpointSecurityTest {

    private static final String BASE_PACKAGE = "com.aetherops";

    @Test
    void noControllerAcceptsAFileUploadYet() {
        List<String> offendingMethods = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        scanner.findCandidateComponents(BASE_PACKAGE).forEach(beanDefinition -> {
            try {
                Class<?> controllerClass = Class.forName(beanDefinition.getBeanClassName());
                for (Method method : controllerClass.getDeclaredMethods()) {
                    for (Parameter parameter : method.getParameters()) {
                        if (acceptsUploadedFile(parameter.getType(), parameter.getParameterizedType())) {
                            offendingMethods.add(controllerClass.getName() + "#" + method.getName());
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Failed to load scanned controller class", e);
            }
        });

        assertThat(offendingMethods)
            .as("A MultipartFile parameter was found on a controller method. If this is a new, "
                + "intentional upload endpoint, it MUST validate content-type against an allow-list "
                + "(never trust the client-supplied filename/extension) and enforce a request size "
                + "limit (spring.servlet.multipart.max-file-size / max-request-size) before this "
                + "assertion is updated to allow it.")
            .isEmpty();
    }

    private boolean acceptsUploadedFile(Class<?> parameterType, Type genericType) {
        if (MultipartFile.class.isAssignableFrom(parameterType)) {
            return true;
        }
        if (parameterType.isArray()) {
            return MultipartFile.class.isAssignableFrom(parameterType.getComponentType());
        }
        // List<MultipartFile> / Collection<MultipartFile>-shaped multi-file upload params.
        if (genericType instanceof ParameterizedType parameterized) {
            for (Type arg : parameterized.getActualTypeArguments()) {
                if (arg instanceof Class<?> argClass && MultipartFile.class.isAssignableFrom(argClass)) {
                    return true;
                }
            }
        }
        return false;
    }
}


/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.example.springaotinfiniterecursive;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.NoProviderFoundException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.ExecutableDescriptor;
import jakarta.validation.metadata.MethodType;
import jakarta.validation.metadata.ParameterDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.ReflectionHints;
import org.springframework.beans.factory.aot.BeanRegistrationAotContribution;
import org.springframework.beans.factory.aot.BeanRegistrationAotProcessor;
import org.springframework.beans.factory.aot.BeanRegistrationCode;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.core.KotlinDetector;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * AOT {@code BeanRegistrationAotProcessor} that adds additional hints
 * required for {@link ConstraintValidator}s.
 *
 * @author Sebastien Deleuze
 * @author Juergen Hoeller
 * @since 6.0.5
 */
class CopyOfBeanValidationBeanRegistrationAotProcessor implements BeanRegistrationAotProcessor {

    private static final boolean beanValidationPresent = ClassUtils.isPresent(
            "jakarta.validation.Validation", CopyOfBeanValidationBeanRegistrationAotProcessor.class.getClassLoader());

    private static final Logger logger = LoggerFactory.getLogger(CopyOfBeanValidationBeanRegistrationAotProcessor.class);


    @Override
    @Nullable
    public BeanRegistrationAotContribution processAheadOfTime(RegisteredBean registeredBean) {
        if (beanValidationPresent) {
            return BeanValidationDelegate.processAheadOfTime(registeredBean);
        }
        return null;
    }


    /**
     * Inner class to avoid a hard dependency on the Bean Validation API at runtime.
     */
    private static class BeanValidationDelegate {

        @Nullable
        private static final Validator validator = getValidatorIfAvailable();

        @Nullable
        private static Validator getValidatorIfAvailable() {
            try (ValidatorFactory validator = Validation.buildDefaultValidatorFactory()) {
                return validator.getValidator();
            } catch (NoProviderFoundException ex) {
                logger.info("No Bean Validation provider available - skipping validation constraint hint inference");
                return null;
            }
        }

        @Nullable
        public static BeanRegistrationAotContribution processAheadOfTime(RegisteredBean registeredBean) {
            if (validator == null) {
                return null;
            }

            Class<?> beanClass = registeredBean.getBeanClass();
            Set<Class<?>> validatedClasses = new HashSet<>();
            Set<Class<? extends ConstraintValidator<?, ?>>> constraintValidatorClasses = new HashSet<>();

            logger.debug("{}", registeredBean.getBeanName());
            processAheadOfTime(beanClass, validatedClasses, constraintValidatorClasses);

            if (!validatedClasses.isEmpty() || !constraintValidatorClasses.isEmpty()) {
                return new AotContribution(validatedClasses, constraintValidatorClasses);
            }
            return null;
        }

        private static void processAheadOfTime(Class<?> clazz, Collection<Class<?>> validatedClasses,
                                               Collection<Class<? extends ConstraintValidator<?, ?>>> constraintValidatorClasses) {

            Assert.notNull(validator, "Validator can't be null");
            if (validatedClasses.contains(clazz)) {
                // Break the infinite recursive loop
                logger.debug("Class {} has already been validated.", clazz.getName());
                return;
            }

            BeanDescriptor descriptor;
            try {
                descriptor = validator.getConstraintsForClass(clazz);
            } catch (RuntimeException ex) {
                if (KotlinDetector.isKotlinType(clazz) && ex instanceof ArrayIndexOutOfBoundsException) {
                    // See https://hibernate.atlassian.net/browse/HV-1796 and https://youtrack.jetbrains.com/issue/KT-40857
                    logger.warn("Skipping validation constraint hint inference for class " + clazz +
                            " due to an ArrayIndexOutOfBoundsException at validator level");
                } else if (ex instanceof TypeNotPresentException) {
                    logger.debug("Skipping validation constraint hint inference for class " +
                            clazz + " due to a TypeNotPresentException at validator level: " + ex.getMessage());
                } else {
                    logger.warn("Skipping validation constraint hint inference for class " + clazz, ex);
                }
                return;
            }

            processExecutableDescriptor(descriptor.getConstrainedMethods(MethodType.NON_GETTER, MethodType.GETTER), constraintValidatorClasses);
            processExecutableDescriptor(descriptor.getConstrainedConstructors(), constraintValidatorClasses);
            processPropertyDescriptors(descriptor.getConstrainedProperties(), constraintValidatorClasses);
            if (!constraintValidatorClasses.isEmpty() && shouldProcess(clazz)) {
                validatedClasses.add(clazz);
            }
            // Add logging here
            logger.debug("Processing clazz: {}", clazz.getName().toString());
            ReflectionUtils.doWithFields(clazz, field -> {
                Class<?> type = field.getType();
                if (Iterable.class.isAssignableFrom(type) || List.class.isAssignableFrom(type) || Optional.class.isAssignableFrom(type)) {
                    ResolvableType resolvableType = ResolvableType.forField(field);
                    Class<?> genericType = resolvableType.getGeneric(0).toClass();
                    if (shouldProcess(genericType)) {
                        validatedClasses.add(clazz);
                        processAheadOfTime(genericType, validatedClasses, constraintValidatorClasses);
                    }
                }
                if (Map.class.isAssignableFrom(type)) {
                    ResolvableType resolvableType = ResolvableType.forField(field);
                    Class<?> keyGenericType = resolvableType.getGeneric(0).toClass();
                    Class<?> valueGenericType = resolvableType.getGeneric(1).toClass();
                    if (shouldProcess(keyGenericType)) {
                        validatedClasses.add(clazz);
                        processAheadOfTime(keyGenericType, validatedClasses, constraintValidatorClasses);
                    }
                    if (shouldProcess(valueGenericType)) {
                        validatedClasses.add(clazz);
                        processAheadOfTime(valueGenericType, validatedClasses, constraintValidatorClasses);
                    }
                }
            });
        }

        private static boolean shouldProcess(Class<?> clazz) {
            return !clazz.getCanonicalName().startsWith("java.");
        }

        private static void processExecutableDescriptor(Set<? extends ExecutableDescriptor> executableDescriptors,
                                                        Collection<Class<? extends ConstraintValidator<?, ?>>> constraintValidatorClasses) {

            for (ExecutableDescriptor executableDescriptor : executableDescriptors) {
                for (ParameterDescriptor parameterDescriptor : executableDescriptor.getParameterDescriptors()) {
                    for (ConstraintDescriptor<?> constraintDescriptor : parameterDescriptor.getConstraintDescriptors()) {
                        constraintValidatorClasses.addAll(constraintDescriptor.getConstraintValidatorClasses());
                    }
                    for (ContainerElementTypeDescriptor typeDescriptor : parameterDescriptor.getConstrainedContainerElementTypes()) {
                        for (ConstraintDescriptor<?> constraintDescriptor : typeDescriptor.getConstraintDescriptors()) {
                            constraintValidatorClasses.addAll(constraintDescriptor.getConstraintValidatorClasses());
                        }
                    }
                }
            }
        }

        private static void processPropertyDescriptors(Set<PropertyDescriptor> propertyDescriptors,
                                                       Collection<Class<? extends ConstraintValidator<?, ?>>> constraintValidatorClasses) {

            for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
                for (ConstraintDescriptor<?> constraintDescriptor : propertyDescriptor.getConstraintDescriptors()) {
                    constraintValidatorClasses.addAll(constraintDescriptor.getConstraintValidatorClasses());
                }
                for (ContainerElementTypeDescriptor typeDescriptor : propertyDescriptor.getConstrainedContainerElementTypes()) {
                    for (ConstraintDescriptor<?> constraintDescriptor : typeDescriptor.getConstraintDescriptors()) {
                        constraintValidatorClasses.addAll(constraintDescriptor.getConstraintValidatorClasses());
                    }
                }
            }
        }
    }


    private static class AotContribution implements BeanRegistrationAotContribution {

        private final Collection<Class<?>> validatedClasses;
        private final Collection<Class<? extends ConstraintValidator<?, ?>>> constraintValidatorClasses;

        public AotContribution(Collection<Class<?>> validatedClasses,
                               Collection<Class<? extends ConstraintValidator<?, ?>>> constraintValidatorClasses) {

            this.validatedClasses = validatedClasses;
            this.constraintValidatorClasses = constraintValidatorClasses;
        }

        @Override
        public void applyTo(GenerationContext generationContext, BeanRegistrationCode beanRegistrationCode) {
            ReflectionHints hints = generationContext.getRuntimeHints().reflection();
            for (Class<?> validatedClass : this.validatedClasses) {
                hints.registerType(validatedClass, MemberCategory.DECLARED_FIELDS);
            }
            for (Class<? extends ConstraintValidator<?, ?>> constraintValidatorClass : this.constraintValidatorClasses) {
                hints.registerType(constraintValidatorClass, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            }
        }
    }

}

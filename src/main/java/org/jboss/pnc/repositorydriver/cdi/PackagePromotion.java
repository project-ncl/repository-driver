package org.jboss.pnc.repositorydriver.cdi;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;

@Qualifier
@Retention(RUNTIME)
@Target({ METHOD, FIELD, PARAMETER, TYPE })
public @interface PackagePromotion {
    final class Literal extends AnnotationLiteral<PackagePromotion> implements PackagePromotion {
        public static final Literal INSTANCE = new Literal();
        private static final long serialVersionUID = 1L;
    }
}

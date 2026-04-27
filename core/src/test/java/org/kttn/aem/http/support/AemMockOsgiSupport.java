package org.kttn.aem.http.support;

import com.adobe.granite.keystore.KeyStoreNotInitialisedException;
import com.adobe.granite.keystore.KeyStoreService;
import io.wcm.testing.mock.aem.junit5.AemContext;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.MockSling;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Test-side OSGi services for wcm.io {@link AemContext} when activating
 * {@link org.kttn.aem.http.impl.HttpClientProviderImpl} without a full AEM stack.
 * <h2>Why not Mockito {@code mock(...)} for Sling/Granite services?</h2>
 * Maven Surefire in this project passes {@code -javaagent:.../mockito-core-...} (see root
 * {@code pom.xml}) so Mockito’s inline mock maker can use Byte Buddy on the classpath. IntelliJ’s
 * default JUnit run configuration usually does <strong>not</strong> add that agent, and mocking
 * {@link ResourceResolverFactory} or {@link com.adobe.granite.keystore.KeyStoreService} can fail
 * with “Could not modify all classes”. This helper uses the real Sling-mock
 * {@link ResourceResolverFactory} and a small {@link KeyStoreService} without Mockito.
 * <h2>Running tests in IntelliJ anyway (optional)</h2>
 * To match Maven, add the same {@code -javaagent} in <em>Run → Edit Configurations → your JUnit
 * template → VM options</em> (and keep {@code -Xshare:off} if you want parity with Surefire), or
 * use “Run with Maven” for a single test class: {@code mvn -pl core test -Dtest=...}.
 */
public final class AemMockOsgiSupport {

    private AemMockOsgiSupport() {
    }

    /**
     * Registers the services {@link org.kttn.aem.http.impl.HttpClientProviderImpl} needs to
     * start in tests: a Sling {@code ResourceResolverFactory} and a no-op {@code KeyStoreService}.
     */
    public static void registerForHttpClientProvider(final AemContext context) {
        registerResourceResolverFactory(context);
        registerUninitializedKeyStoreService(context);
    }

    public static void registerResourceResolverFactory(final AemContext context) {
        final ResourceResolverFactory factory = MockSling.newResourceResolverFactory(
            context.resourceResolverType(),
            context.bundleContext());
        context.registerService(ResourceResolverFactory.class, factory);
    }

    /**
     * Granite {@code KeyStoreService} that behaves like “trust store not initialised”. Matches
     * the production code path in {@code HttpClientProviderImpl} that logs and uses the JVM trust
     * store only, without using Mockito.
     */
    public static void registerUninitializedKeyStoreService(final AemContext context) {
        final KeyStoreService stub = (KeyStoreService) Proxy.newProxyInstance(
            KeyStoreService.class.getClassLoader(),
            new Class<?>[] {KeyStoreService.class},
            new UninitializedKeyStore());
        context.registerService(KeyStoreService.class, stub);
    }

    private static final class UninitializedKeyStore implements InvocationHandler {
        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            // Handle Object methods
            if ("hashCode".equals(method.getName()) && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName()) && method.getParameterCount() == 1) {
                return proxy == args[0];
            }
            if ("toString".equals(method.getName()) && method.getParameterCount() == 0) {
                return "UninitializedKeyStore@" + Integer.toHexString(System.identityHashCode(proxy));
            }

            // Handle KeyStoreService methods
            if ("getTrustManager".equals(method.getName()) && method.getParameterCount() == 1) {
                throw new KeyStoreNotInitialisedException(
                    "AEM test stub: Granite trust store not used in this unit test");
            }
            final Class<?> returnType = method.getReturnType();
            if (returnType == void.class) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType.isPrimitive()) {
                // Handle other primitive types
                if (returnType == int.class) return 0;
                if (returnType == long.class) return 0L;
                if (returnType == byte.class) return (byte) 0;
                if (returnType == short.class) return (short) 0;
                if (returnType == char.class) return '\0';
                if (returnType == float.class) return 0.0f;
                if (returnType == double.class) return 0.0d;
            }
            return null;
        }
    }
}

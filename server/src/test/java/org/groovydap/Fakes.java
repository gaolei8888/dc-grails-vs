package org.groovydap;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** JDI interfaces as dynamic proxies, so value rendering is testable without a VM. */
final class Fakes {

    /** Every call a fake saw that would run code in the target VM; must stay empty. */
    static final java.util.List<String> INVOCATIONS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private Fakes() {
    }

    @SuppressWarnings("unchecked")
    static <T> T of(Class<T> type, String label, Map<String, Function<Object[], Object>> methods) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    // ObjectReference/ClassType/ThreadReference.invokeMethod, newInstance:
                    // the only ways to run application code. Reading is never one of them.
                    // A fake that lists one in `methods` has opted in (a check that pins
                    // exactly which invocations are allowed); anything else still fails.
                    if ((method.getName().startsWith("invoke") || method.getName().equals("newInstance"))
                            && !methods.containsKey(method.getName())) {
                        INVOCATIONS.add(label + "." + method.getName());
                        throw new AssertionError("read-only violated: " + label + "." + method.getName());
                    }
                    Function<Object[], Object> impl = methods.get(method.getName());
                    if (impl != null) {
                        return impl.apply(args);
                    }
                    switch (method.getName()) {
                        case "toString": return label;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    type.getSimpleName() + "." + method.getName());
                    }
                });
    }

    static Map<String, Function<Object[], Object>> methods(Object... pairs) {
        Map<String, Function<Object[], Object>> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            @SuppressWarnings("unchecked")
            Function<Object[], Object> fn = value instanceof Function
                    ? (Function<Object[], Object>) value : a -> value;
            out.put((String) pairs[i], fn);
        }
        return out;
    }
}

package org.groovydap.jdi;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

/**
 * Reads collections by walking them, never by calling them.
 *
 * <p>A HashMap keeps its entries in a table of nodes with a next chain; an
 * ArrayList keeps its elements in {@code elementData} with a size. Both are a
 * handful of field reads. Calling {@code get(key)} would be shorter, but invoking
 * a method in the target VM means resuming threads to run it, and a debugger that
 * runs application code to answer a hover can deadlock or change what it is
 * showing.
 */
public final class MapReader {

    /**
     * Found-or-not, kept apart from the value itself.
     *
     * <p>A map can hold a null, and JDI represents a null reference as a Java
     * null, so returning null for "no such key" would make the two cases the same
     * one -- a key present with a null value would read as missing.
     */
    public static final class Lookup {
        public final boolean found;
        public final Value value;

        private Lookup(boolean found, Value value) {
            this.found = found;
            this.value = value;
        }

        static Lookup of(Value value) {
            return new Lookup(true, value);
        }

        static Lookup absent() {
            return new Lookup(false, null);
        }
    }

    private MapReader() {
    }

    /**
     * The entry table of a HashMap-shaped map, following one level of wrapper.
     *
     * <p>The wrapper step is for GrailsParameterMap, which is not a map itself --
     * it holds one in {@code wrappedMap} alongside a request and a date cache.
     */
    public static ArrayReference tableOf(ObjectReference object) {
        Field table = object.referenceType().fieldByName("table");
        if (table != null) {
            Value value = object.getValue(table);
            if (value instanceof ArrayReference) {
                return (ArrayReference) value;
            }
        }
        Field wrapped = object.referenceType().fieldByName("wrappedMap");
        if (wrapped != null) {
            Value value = object.getValue(wrapped);
            if (value instanceof ObjectReference) {
                ObjectReference inner = (ObjectReference) value;
                Field innerTable = inner.referenceType().fieldByName("table");
                if (innerTable != null) {
                    Value found = inner.getValue(innerTable);
                    if (found instanceof ArrayReference) {
                        return (ArrayReference) found;
                    }
                }
            }
        }
        return null;
    }

    /** The value for a string key, or absent if this is not a map or has no such key. */
    public static Lookup get(ObjectReference object, String key) {
        try {
            ArrayReference table = tableOf(object);
            if (table == null) {
                return Lookup.absent();
            }
            for (Value slot : table.getValues()) {
                ObjectReference node = slot instanceof ObjectReference
                        ? (ObjectReference) slot : null;
                int guard = 0;
                while (node != null && guard++ < 64) {
                    Field keyField = node.referenceType().fieldByName("key");
                    Field valueField = valueField(node);
                    if (keyField == null || valueField == null) {
                        break;
                    }
                    Value entryKey = node.getValue(keyField);
                    if (entryKey instanceof StringReference
                            && key.equals(((StringReference) entryKey).value())) {
                        return Lookup.of(node.getValue(valueField));
                    }
                    Field next = node.referenceType().fieldByName("next");
                    Value following = next == null ? null : node.getValue(next);
                    node = following instanceof ObjectReference ? (ObjectReference) following : null;
                }
            }
        } catch (Exception e) {
            return Lookup.absent();
        }
        return Lookup.absent();
    }

    /** The element at an index of an ArrayList-shaped list, or absent. */
    public static Lookup element(ObjectReference object, int index) {
        try {
            Field data = object.referenceType().fieldByName("elementData");
            if (data == null) {
                return Lookup.absent();
            }
            Value value = object.getValue(data);
            if (!(value instanceof ArrayReference)) {
                return Lookup.absent();
            }
            ArrayReference array = (ArrayReference) value;
            if (index < 0 || index >= array.length()) {
                return Lookup.absent();
            }
            return Lookup.of(array.getValue(index));
        } catch (Exception e) {
            return Lookup.absent();
        }
    }

    /** HashMap calls it value; ConcurrentHashMap calls it val. */
    static Field valueField(ObjectReference node) {
        Field field = node.referenceType().fieldByName("value");
        return field != null ? field : node.referenceType().fieldByName("val");
    }
}

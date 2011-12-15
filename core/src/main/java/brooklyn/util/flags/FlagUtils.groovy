package brooklyn.util.flags;

import java.lang.reflect.Field


/** class to help transfer values passed as named arguments to other well-known variables/fields/objects;
 * see the test case for example usage */
public class FlagUtils {

    private FlagUtils() {}
    
    /** sets all public fields (local and inherited) on the given object from the given flags map, returning unknown elements */
    public static Map setPublicFieldsFromFlags(Map flags, Object o) {
        setFieldsFromFlags(flags, o, o.getClass().getFields() as Set)
    }
    /** sets all fields (including private and static) on the given object and all supertypes, 
     * from the given flags map, returning just those flags which are not applicable */
    public static Map setFieldsFromFlags(Map flags, Object o) {
        setFieldsFromFlags(flags, o, getAllFields(o.getClass()))
    }
	
	/** returns all fields on the given class, superclasses, and interfaces thereof, in that order of preference,
	 * (excluding fields on Object) */
	public static List getAllFields(Class base, Closure filter={true}) {
		getLocalFields(getAllAssignableTypes(base), filter);
	}
	/** returns all fields explicitly declared on the given classes */
	public static List getLocalFields(List classes, Closure filter={true}) {
		List fields = []
		classes.each { Class c -> c.getDeclaredFields().each { Field f -> if (filter.call(f)) fields << f }}
		fields
	}
	/** returns base, superclasses, then interfaces */
	public static List getAllAssignableTypes(Class base, Closure filter={ (it!=Object) && (it!=GroovyObject) }) {
		List classes = []
		for (Class c = base; c!=null; c=c.getSuperclass()) { if (filter.call(c)) classes << c }
		for (int i=0; i<classes.size(); i++) {
			classes.get(i).getInterfaces().each { c -> if (filter.call(c) && !(classes.contains(c))) classes << c } 
		}
		classes
	}
	
    private static Map setFieldsFromFlags(Map flags, Object o, Collection<Field> fields) {
        Map remaining=[:]
		if (flags) remaining += flags
        for (Field f: fields) {
            SetFromFlag cf = f.getAnnotation(SetFromFlag.class);
            if (cf) {
                String flagName = cf.value() ?: f.getName();
                if (flagName && remaining.containsKey(flagName)) {
                    setField(o, f, remaining.remove(flagName), cf);
                }
            }
        }
        return remaining
    }

    /** sets the field to the value, after checking whether the given value can be set 
     * respecting the constraints of the annotation */
    public static Object setField(Object objectOfField, Field f, Object value, SetFromFlag annotation) {
        if (!f.isAccessible()) f.setAccessible(true)
        if (annotation.immutable()) {
            def oldValue = f.get(objectOfField)
            if (oldValue!=getDefaultValueForType(f.getType()) && oldValue!=value) {
                throw new IllegalStateException("Forbidden modification to immutable field "+
                    "$f in $objectOfField: attempting to change to $value when was already $oldValue");
            }
        }
        if (!annotation.nullable() && value==null) {
            throw new IllegalStateException("Forbidden null assignment to non-nullable field "+
                    "$f in $objectOfField");
        }
        f.set(objectOfField, value)
    }
    
    /** returns the default/inital value that is assigned to fields of the givien type;
     * if the type is not primitive this value is null;
     * for primitive types it is obvious but not AFAIK programmatically visible
     * (e.g. 0 for int, false for boolean)  
     */
    public static Object getDefaultValueForType(Class t) {
        if (!t.isPrimitive()) return null;
        if (t==Integer.TYPE) return (int)0;
        if (t==Long.TYPE) return (long)0;
        if (t==Double.TYPE) return (double)0;
        if (t==Float.TYPE) return (float)0;
        if (t==Byte.TYPE) return (byte)0;
        if (t==Short.TYPE) return (short)0;
        if (t==Character.TYPE) return (char)0;
        if (t==Boolean.TYPE) return false;
        //should never happen
        throw new IllegalStateException("Class $t is an unknown primitive.");
    }

	/** returns a map of all fields which are annotated 'SetFromFlag' with their current values;
	 * useful if you want to clone settings from one object
	 */
	public static Map getFieldsWithValues(Object o) {
		Map result=[:]
		for (Field f: getAllFields(o.getClass())) {
			SetFromFlag cf = f.getAnnotation(SetFromFlag.class);
			if (cf) {
				String flagName = cf.value() ?: f.getName();
				if (flagName) {
					if (!f.isAccessible()) f.setAccessible(true)
					result.put(flagName, f.get(o))
				}
			}
		}
		return result
	}
}

/*
 * Copyright 2015 Odnoklassniki Ltd, Mail.Ru Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package one.nio.serial;

import one.nio.gen.BytecodeGenerator;
import one.nio.mgt.Management;
import one.nio.util.Base64;
import one.nio.util.JavaInternals;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.Externalizable;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static java.nio.file.StandardOpenOption.*;

public class Repository {
    public static final Log log = LogFactory.getLog(Repository.class);

    static final byte[][] classLocks = new byte[64][0];
    static final ConcurrentHashMap<Class, Serializer> classMap = new ConcurrentHashMap<>(128);
    static final ConcurrentHashMap<Long, Serializer> uidMap = new ConcurrentHashMap<>(128);
    static final ConcurrentHashMap<Method, MethodSerializer> methodMap = new ConcurrentHashMap<>();
    static final Serializer[] bootstrapSerializers = new Serializer[128];
    static final ConcurrentHashMap<Class, Integer> serializationOptions = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Class> renamedClasses = new ConcurrentHashMap<>();
    static final AtomicInteger anonymousClasses = new AtomicInteger();
    static final int ENUM = 0x4000;
    static private BiConsumer<Class, Serializer<?>> serializerGenerated = (cls, serializer) -> {
    };

    public static final MethodSerializer provide =
            registerMethod(JavaInternals.findMethod(Repository.class, "provideSerializer", Serializer.class));
    public static final MethodSerializer request =
            registerMethod(JavaInternals.findMethod(Repository.class, "requestSerializer", long.class));

    public static final int SKIP_READ_OBJECT  = 1;
    public static final int SKIP_WRITE_OBJECT = 2;
    public static final int SKIP_CUSTOM_SERIALIZATION = SKIP_READ_OBJECT | SKIP_WRITE_OBJECT;
    public static final int INLINE = 4;
    public static final int FIELD_SERIALIZATION = 8;
    public static final int SYNTHETIC_FIELDS = 16;

    public static final int ARRAY_STUBS      = 1;
    public static final int COLLECTION_STUBS = 2;
    public static final int MAP_STUBS        = 4;
    public static final int ENUM_STUBS       = 8;
    public static final int CUSTOM_STUBS     = 16;
    public static final int DEFAULT_OPTIONS  = ARRAY_STUBS | COLLECTION_STUBS | MAP_STUBS | ENUM_STUBS | CUSTOM_STUBS;

    private static long nextBootstrapUid = -10;
    private static int options = Integer.getInteger("one.nio.serial.options", DEFAULT_OPTIONS);

    static {
        addBootstrap(new IntegerSerializer());
        addBootstrap(new LongSerializer());
        addBootstrap(new BooleanSerializer());
        addBootstrap(new ByteSerializer());
        addBootstrap(new ShortSerializer());
        addBootstrap(new CharacterSerializer());
        addBootstrap(new FloatSerializer());
        addBootstrap(new DoubleSerializer());
        addBootstrap(new StringSerializer());
        addBootstrap(new DateSerializer());
        addBootstrap(new ClassSerializer());
        addBootstrap(new BitSetSerializer());

        addBootstrap(new BooleanArraySerializer());
        addBootstrap(new ByteArraySerializer());
        addBootstrap(new ShortArraySerializer());
        addBootstrap(new CharacterArraySerializer());
        addBootstrap(new IntegerArraySerializer());
        addBootstrap(new LongArraySerializer());
        addBootstrap(new FloatArraySerializer());
        addBootstrap(new DoubleArraySerializer());

        addBootstrap(new ObjectArraySerializer(Object[].class));
        addBootstrap(new ObjectArraySerializer(String[].class));
        addBootstrap(new ObjectArraySerializer(Class[].class));
        addBootstrap(new ObjectArraySerializer(Long[].class));

        addBootstrap(new ArrayListSerializer());
        addBootstrap(new CollectionSerializer(LinkedList.class));
        addBootstrap(new CollectionSerializer(Vector.class));
        addBootstrap(new CollectionSerializer(HashSet.class));
        addBootstrap(new CollectionSerializer(TreeSet.class));
        addBootstrap(new CollectionSerializer(LinkedHashSet.class));

        addBootstrap(new HashMapSerializer());
        addBootstrap(new MapSerializer(TreeMap.class));
        addBootstrap(new MapSerializer(LinkedHashMap.class));
        addBootstrap(new MapSerializer(Hashtable.class));
        addBootstrap(new MapSerializer(IdentityHashMap.class));
        addBootstrap(new MapSerializer(ConcurrentHashMap.class));
        
        addBootstrap(new SerializerSerializer(ObjectArraySerializer.class));
        addBootstrap(new SerializerSerializer(EnumSerializer.class));
        addBootstrap(new SerializerSerializer(CollectionSerializer.class));
        addBootstrap(new SerializerSerializer(MapSerializer.class));
        addBootstrap(new SerializerSerializer(ExternalizableSerializer.class));
        addBootstrap(new SerializerSerializer(GeneratedSerializer.class));
        addBootstrap(new ExternalizableSerializer(SerializerNotFoundException.class));

        addOptionalBootstrap("one.app.remote.reflect.MethodId");
        addOptionalBootstrap("one.app.remote.comp.RemoteMethodCallRequest");

        addBootstrap(new TimestampSerializer());
        addBootstrap(new RemoteCallSerializer());
        addBootstrap(new SerializerSerializer(MethodSerializer.class));

        addBootstrap(new SqlDateSerializer());
        addBootstrap(new SqlTimeSerializer());

        // Unable to run readObject/writeObject for the following classes.
        // Fortunately standard serialization works well for them.
        setOptions(InetAddress.class, SKIP_CUSTOM_SERIALIZATION);
        setOptions(InetSocketAddress.class, SKIP_CUSTOM_SERIALIZATION);
        setOptions(StringBuilder.class, SKIP_CUSTOM_SERIALIZATION);
        setOptions(StringBuffer.class, SKIP_CUSTOM_SERIALIZATION);
        setOptions(BigInteger.class, SKIP_CUSTOM_SERIALIZATION);

        // At some moment InetAddress fields were moved to an auxilary holder class.
        // This resolves backward compatibility problem by inlining holder fields during serialization.
        setOptions("java.net.InetAddress$InetAddressHolder", INLINE);
        setOptions("java.net.InetSocketAddress$InetSocketAddressHolder", INLINE);

        Management.registerMXBean(new SerializationMXBeanImpl(), "one.nio.serial:type=Serialization");
    }
    
    private static void addBootstrap(Serializer serializer) {
        serializer.uid = nextBootstrapUid--;
        provideSerializer(serializer);
    }

    private static void addOptionalBootstrap(String className) {
        try {
            addBootstrap(generateFor(Class.forName(className)));
        } catch (ClassNotFoundException e) {
            // Optional class is missing. Skip the slot to maintain the order of other bootstrap serializers.
            addBootstrap(new InvalidSerializer(className));
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Serializer<T> get(Class<T> cls) {
        Serializer result = classMap.get(cls);
        return result != null ? result : generateFor(cls);
    }

    public static MethodSerializer get(Method method) {
        return methodMap.get(method);
    }

    public static MethodSerializer registerMethod(Method method) {
        MethodSerializer result = methodMap.get(method);
        if (result == null) {
            result = new MethodSerializer(method);
            provideSerializer(result);
        }
        return result;
    }

    public static boolean preload(Class... classes) {
        for (Class<?> cls : classes) {
            get(cls);
        }
        return true;
    }

    public static boolean hasSerializer(long uid) {
        return uidMap.containsKey(uid);
    }

    public static Serializer requestSerializer(long uid) throws SerializerNotFoundException {
        Serializer result = uidMap.get(uid);
        if (result != null) {
            return result;
        }
        throw new SerializerNotFoundException(uid);
    }

    public static Serializer requestBootstrapSerializer(byte uid) {
        return bootstrapSerializers[128 + uid];
    }

    public static void provideSerializer(Serializer serializer) {
        Serializer oldSerializer = uidMap.put(serializer.uid, serializer);
        if (oldSerializer != null && oldSerializer.cls != serializer.cls) {
            throw new IllegalStateException("UID collision: " + serializer.descriptor + " overwrites " + oldSerializer.descriptor);
        }

        if (serializer.uid < 0) {
            bootstrapSerializers[128 + (int) serializer.uid] = serializer;
        }

        if (serializer.origin != Origin.EXTERNAL) {
            if (serializer instanceof MethodSerializer) {
                MethodSerializer methodSerializer = (MethodSerializer) serializer;
                methodMap.put(methodSerializer.method, methodSerializer);
            } else {
                classMap.put(serializer.cls, serializer);
            }
        }
    }

    public static void provideSerializer(String base64) {
        try {
            byte[] serialForm = Base64.decodeFromChars(base64.toCharArray());
            provideSerializer((Serializer) Serializer.deserialize(serialForm));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static void setOptions(String className, int options) {
        try {
            Class cls = Class.forName(className, false, BytecodeGenerator.INSTANCE);
            setOptions(cls, options);
        } catch (ClassNotFoundException e) {
            // Ignore
        }
    }

    public static void setOptions(Class cls, int options) {
        serializationOptions.put(cls, options);
        classMap.remove(cls);
    }

    public static boolean hasOptions(Class cls, int options) {
        Integer value = serializationOptions.get(cls);
        return value != null && (value & options) == options;
    }

    public static void setOptions(int options) {
        Repository.options = options;
    }

    public static int getOptions() {
        return options;
    }

    public static byte[] saveSnapshot() throws IOException {
        Serializer[] serializers = uidMap.values().toArray(new Serializer[0]);

        CalcSizeStream css = new CalcSizeStream();
        for (Serializer serializer : serializers) {
            if (serializer.uid >= 0) {
                css.writeObject(serializer);
            }
        }

        byte[] snapshot = new byte[css.count()];
        SerializeStream ss = new SerializeStream(snapshot);
        for (Serializer serializer : serializers) {
            if (serializer.uid >= 0) {
                ss.writeObject(serializer);
            }
        }

        return snapshot;
    }

    public static void saveSnapshot(String fileName) throws IOException {
        byte[] snapshot = saveSnapshot();
        Files.write(Paths.get(fileName), snapshot, WRITE, CREATE, TRUNCATE_EXISTING);
    }

    public static int loadSnapshot(byte[] snapshot) throws IOException, ClassNotFoundException {
        int count = 0;
        DeserializeStream ds = new DeserializeStream(snapshot);
        while (ds.available() > 0) {
            provideSerializer((Serializer) ds.readObject());
            count++;
        }
        return count;
    }

    public static int loadSnapshot(String fileName) throws IOException, ClassNotFoundException {
        byte[] snapshot = Files.readAllBytes(Paths.get(fileName));
        return loadSnapshot(snapshot);
    }

    private static Serializer generateFor(Class<?> cls) {
        synchronized (classLockFor(cls)) {
            Serializer serializer = classMap.get(cls);
            if (serializer != null) {
                return serializer;
            }

            if (cls.isAnonymousClass() && (cls.getModifiers() & ENUM) == 0) {
                log.warn("Trying to serialize anonymous class: " + cls.getName());
                anonymousClasses.incrementAndGet();
            }

            SerialOptions options = cls.getAnnotation(SerialOptions.class);
            if (options != null) {
                setOptions(cls, options.value());
            }

            Renamed renamed = cls.getAnnotation(Renamed.class);
            if (renamed != null) {
                renamedClasses.put(renamed.from(), cls);
            }

            if (cls.isArray()) {
                get(cls.getComponentType());
                serializer = new ObjectArraySerializer(cls);
            } else if ((cls.getModifiers() & ENUM) != 0) {
                if (cls.getSuperclass() != Enum.class) {
                    serializer = get(cls.getSuperclass());
                    classMap.put(cls, serializer);
                    return serializer;
                }
                serializer = new EnumSerializer(cls);
            } else if (Externalizable.class.isAssignableFrom(cls)) {
                if (Serializer.class.isAssignableFrom(cls)) {
                    serializer = new SerializerSerializer(cls);
                } else {
                    serializer = new ExternalizableSerializer(cls);
                }
            } else if (Collection.class.isAssignableFrom(cls) && !hasOptions(cls, FIELD_SERIALIZATION)) {
                serializer = new CollectionSerializer(cls);
            } else if (Map.class.isAssignableFrom(cls) && !hasOptions(cls, FIELD_SERIALIZATION)) {
                serializer = new MapSerializer(cls);
            } else if (Serializable.class.isAssignableFrom(cls)) {
                serializer = new GeneratedSerializer(cls);
            } else {
                serializer = new InvalidSerializer(cls);
            }

            serializer.generateUid();
            provideSerializer(serializer);
            return serializer;
        }
    }

    public static void setSerializerGeneratedHook(BiConsumer<Class, Serializer<?>> hook) {
        serializerGenerated = hook;
    }

    private static Object classLockFor(Class<?> cls) {
        return classLocks[cls.hashCode() & (classLocks.length - 1)];
    }
}

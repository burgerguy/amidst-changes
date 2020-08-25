package amidst.mojangapi.minecraftinterface.local;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import amidst.clazz.symbolic.SymbolicClass;
import amidst.clazz.symbolic.SymbolicObject;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.minecraftinterface.RecognisedVersion;
import amidst.mojangapi.minecraftinterface.UnsupportedDimensionException;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.WorldType;
import amidst.util.ArrayCache;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.entrypoint.minecraft.hooks.EntrypointUtils;

public class LocalMinecraftInterface implements MinecraftInterface {

	private static String STRING_WITH_ZERO_HASHCODE = "drumwood boulderhead";
	private static Set<Dimension> SUPPORTED_DIMENSIONS = Collections.unmodifiableSet(EnumSet.of(Dimension.OVERWORLD, Dimension.NETHER));

    private boolean isInitialized = false;
	private final RecognisedVersion recognisedVersion;

	private final SymbolicClass registryClass;
	private final SymbolicClass registryAccessClass;
	private final SymbolicClass resourceKeyClass;
	private final SymbolicClass worldGenSettingsClass;
	private final SymbolicClass dimensionSettingsClass;
	private final SymbolicClass noiseBiomeProviderClass;
	private final SymbolicClass biomeZoomerClass;
	private final SymbolicClass utilClass;

	private MethodHandle registryGetIdMethod;
    private MethodHandle biomeProviderGetBiomeMethod;
    private MethodHandle biomeZoomerGetBiomeMethod;

    private Object registryAccess; // Default registry to use when creating worlds (after 20w28a)
	private Object biomeRegistry;

	private Object overworldResourceKey;
	private Object netherResourceKey;

    /**
     * An array used to return biome data
     */
    private final ArrayCache<int[]> dataArray = ArrayCache.makeIntArrayCache(256);

	public LocalMinecraftInterface(Map<String, SymbolicClass> symbolicClassMap, RecognisedVersion recognisedVersion) {
		this.recognisedVersion = recognisedVersion;
		this.registryClass = symbolicClassMap.get(SymbolicNames.CLASS_REGISTRY);
		this.registryAccessClass = symbolicClassMap.get(SymbolicNames.CLASS_REGISTRY_ACCESS);
        this.resourceKeyClass = symbolicClassMap.get(SymbolicNames.CLASS_RESOURCE_KEY);
        this.worldGenSettingsClass = symbolicClassMap.get(SymbolicNames.CLASS_WORLD_GEN_SETTINGS);

        this.dimensionSettingsClass = symbolicClassMap.get(SymbolicNames.CLASS_DIMENSION_SETTINGS);
        this.noiseBiomeProviderClass = symbolicClassMap.get(SymbolicNames.CLASS_NOISE_BIOME_PROVIDER);
        this.biomeZoomerClass = symbolicClassMap.get(SymbolicNames.CLASS_BIOME_ZOOMER);
        this.utilClass = symbolicClassMap.get(SymbolicNames.CLASS_UTIL);
	}

	@Override
	public synchronized MinecraftInterface.World createWorld(long seed, WorldType worldType, String generatorOptions)
			throws MinecraftInterfaceException {
	    initializeIfNeeded();

	    try {
	    	Object worldSettings = createWorldSettingsObject(seed, worldType, generatorOptions).getObject();
			ThreadLocal<Object> threadedOWBProvider;
			ThreadLocal<Object> threadedNetherBProvider;
	    	if (dimensionSettingsClass == null) {
	    		final Map<?, ?> generators = (Map<?, ?>) callParameterlessMethodReturning(worldSettings, Map.class);
	    		threadedOWBProvider = ThreadLocal.withInitial(() -> {
					try {
						return getBiomesFromGeneratorsMap(generators, overworldResourceKey);
					} catch (MinecraftInterfaceException
							| IllegalArgumentException
							| IllegalAccessException
							| InvocationTargetException e) {
						throw new RuntimeException(e);
					}
				});
	    		threadedNetherBProvider = ThreadLocal.withInitial(() -> {
					try {
						return getBiomesFromGeneratorsMap(generators, netherResourceKey);
					} catch (MinecraftInterfaceException
							| IllegalArgumentException
							| IllegalAccessException
							| InvocationTargetException e) {
						throw new RuntimeException(e);
					}
	    		});
			} else {
				final Object dimensions = callParameterlessMethodReturning(worldSettings, registryClass.getClazz());
				threadedOWBProvider = ThreadLocal.withInitial(() -> {
					try {
						return getBiomesFromDimensionRegistry(dimensions, overworldResourceKey);
					} catch (MinecraftInterfaceException
							| IllegalArgumentException
							| IllegalAccessException
							| InvocationTargetException e) {
						throw new RuntimeException(e);
					}
				});
				threadedNetherBProvider = ThreadLocal.withInitial(() -> {
					try {
						return getBiomesFromDimensionRegistry(dimensions, netherResourceKey);
					} catch (MinecraftInterfaceException
							| IllegalArgumentException
							| IllegalAccessException
							| InvocationTargetException e) {
						throw new RuntimeException(e);
					}
				});
			}

            long seedForBiomeZoomer = makeSeedForBiomeZoomer(seed);
	        Object biomeZoomer = biomeZoomerClass.getClazz().getEnumConstants()[0];
            return new World(threadedOWBProvider, threadedNetherBProvider, biomeZoomer, seedForBiomeZoomer);

        } catch(RuntimeException | IllegalAccessException | InvocationTargetException e) {
            throw new MinecraftInterfaceException("unable to create world", e);
        }
	}

	private static long makeSeedForBiomeZoomer(long seed) throws MinecraftInterfaceException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
			buf.putLong(seed);
			byte[] bytes = digest.digest(buf.array());

			long result = 0;
			for (int i = 0; i < 8; i++) {
				result |= (bytes[i] & 0xffL) << (i*8L);
			}
			return result;
		} catch (NoSuchAlgorithmException e) {
			throw new MinecraftInterfaceException("unable to hash seed for biome zoomer", e);
		}
	}
	private SymbolicObject createWorldSettingsObject(long seed, WorldType worldType, String generatorOptions)
			throws IllegalAccessException, InvocationTargetException, MinecraftInterfaceException {
		Properties worldProperties = new Properties();
		// Minecraft interprets "0" as a random seed, so provide a string hashing to 0 instead
		worldProperties.setProperty("level-seed", seed == 0 ? STRING_WITH_ZERO_HASHCODE : Long.toString(seed));
		worldProperties.setProperty("level-type", getTrueWorldTypeName(worldType));
		worldProperties.setProperty("generator-settings", generatorOptions);

		if (worldGenSettingsClass.hasMethod(SymbolicNames.METHOD_WORLD_GEN_SETTINGS_CREATE)) {
			return (SymbolicObject) worldGenSettingsClass.callStaticMethod(
					SymbolicNames.METHOD_WORLD_GEN_SETTINGS_CREATE, worldProperties);
		} else {
			Objects.requireNonNull(registryAccess);
			return (SymbolicObject) worldGenSettingsClass.callStaticMethod(
					SymbolicNames.METHOD_WORLD_GEN_SETTINGS_CREATE2, registryAccess, worldProperties);
		}
	}
	/*
	private Object createBiomeProviderObject(long seed, WorldType worldType, String generatorOptions)
			throws IllegalAccessException, InvocationTargetException, MinecraftInterfaceException {
		Properties worldProperties = new Properties();
		// Minecraft interprets "0" as a random seed, so provide a string hashing to 0 instead
		worldProperties.setProperty("level-seed", seed == 0 ? STRING_WITH_ZERO_HASHCODE : Long.toString(seed));
		worldProperties.setProperty("level-type", getTrueWorldTypeName(worldType));
		worldProperties.setProperty("generator-settings", generatorOptions);

		Object worldSettings = RecognisedVersion.isNewerOrEqualTo(recognisedVersion, RecognisedVersion._1_16_2) ?
				worldGenSettingsClass.getMethod(SymbolicNames.METHOD_WORLD_GEN_SETTINGS_CREATE2).getRawMethod().invoke(null, defaultDynamicRegistry, worldProperties) :
				worldGenSettingsClass.getMethod(SymbolicNames.METHOD_WORLD_GEN_SETTINGS_CREATE).getRawMethod().invoke(null, worldProperties);

		// This is more robust than declaring a symbolic method, if the name ever changes
		Object chunkGenerator = null;
		for (Method meth : worldGenSettingsClass.getClazz().getMethods()) {
			if (meth.getParameterCount() == 0
					&& chunkGeneratorClass.getClazz().isAssignableFrom(meth.getReturnType())) {
				chunkGenerator = meth.invoke(worldSettings);
				break;
			}
		}



		for (Method meth : chunkGeneratorClass.getClazz().getMethods()) {
			if (meth.getParameterCount() == 0
					&& noiseBiomeProviderClass.getClazz().isAssignableFrom(meth.getReturnType())) {
				return meth.invoke(chunkGenerator);
			}
		}

		throw new MinecraftInterfaceException("Couldn't retrieve biome provider from chunk generator");
	}
	 */

	private Object getBiomesFromDimensionRegistry(Object dimensionRegistry, Object key)
			throws IllegalAccessException, InvocationTargetException, MinecraftInterfaceException {
		SymbolicObject registry = new SymbolicObject(registryClass, dimensionRegistry);
		SymbolicObject dimension = new SymbolicObject(
				dimensionSettingsClass,
				registry.callMethod(SymbolicNames.METHOD_REGISTRY_GET_BY_KEY, key)
		);
		Object generator = dimension.getFieldValue(SymbolicNames.FIELD_DIMENSION_SETTINGS_GENERATOR);
		return callParameterlessMethodReturning(generator, noiseBiomeProviderClass.getClazz());
	}

	private Object getBiomesFromGeneratorsMap(Map<?, ?> generators, Object key)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, MinecraftInterfaceException {
		String stringKey = key.toString();
		for (Map.Entry<?, ?> entry: generators.entrySet()) {
			if(stringKey.equals(entry.getKey().toString())) {
				return callParameterlessMethodReturning(entry.getValue(), noiseBiomeProviderClass.getClazz());
			}
		}
		return null;
	}

	private static String getTrueWorldTypeName(WorldType worldType) {
		switch (worldType) {
		case DEFAULT:
			return "default";
		case FLAT:
			return "flat";
		case AMPLIFIED:
			return "amplified";
		case LARGE_BIOMES:
			return "largeBiomes";
		case CUSTOMIZED:
			return "customized";
		default:
			AmidstLogger.warn("Unsupported world type for this version: " + worldType);
			return "";
		}
	}

	@Override
	public RecognisedVersion getRecognisedVersion() {
		return recognisedVersion;
	}

	private synchronized void initializeIfNeeded() throws MinecraftInterfaceException {
		if (isInitialized) {
			return;
		}

		try {
			if (registryAccessClass == null) {
				registryAccess = null;
				biomeRegistry = getLegacyBiomeRegistry();

				registryGetIdMethod = getMethodHandle(registryClass, SymbolicNames.METHOD_REGISTRY_GET_ID);
			} else {
				Object key = ((SymbolicObject) registryClass
						.callStaticMethod(SymbolicNames.METHOD_REGISTRY_CREATE_KEY, "worldgen/biome"))
						.getObject();
				// We don't use symbolic calls, because they are inconsistently wrapped in SymbolicObject.
				registryAccess = registryAccessClass.getMethod(SymbolicNames.METHOD_REGISTRY_ACCESS_BUILTIN)
						.getRawMethod().invoke(null);
				biomeRegistry = registryAccessClass.getMethod(SymbolicNames.METHOD_REGISTRY_ACCESS_GET_REGISTRY)
						.getRawMethod().invoke(registryAccess, key);
				biomeRegistry = Objects.requireNonNull(biomeRegistry);

				registryGetIdMethod = getMethodHandle(registryClass, SymbolicNames.METHOD_REGISTRY_GET_ID2);
			}

			stopAllExecutors();

			EntrypointUtils.invoke("main", ModInitializer.class, ModInitializer::onInitialize);

			biomeProviderGetBiomeMethod = getMethodHandle(noiseBiomeProviderClass, SymbolicNames.METHOD_NOISE_BIOME_PROVIDER_GET_BIOME);
			biomeZoomerGetBiomeMethod = getMethodHandle(biomeZoomerClass, SymbolicNames.METHOD_BIOME_ZOOMER_GET_BIOME);

			overworldResourceKey = createResourceKey("overworld");
			netherResourceKey = createResourceKey("the_nether");
		} catch(IllegalArgumentException | IllegalAccessException | InstantiationException
				| InvocationTargetException e) {
			throw new MinecraftInterfaceException("unable to initialize the MinecraftInterface", e);
		}

		isInitialized = true;
	}

	private Object getLegacyBiomeRegistry() throws IllegalArgumentException, IllegalAccessException,
    	InstantiationException, InvocationTargetException, MinecraftInterfaceException {
    	Object metaRegistry = registryClass.getStaticFieldValue(SymbolicNames.FIELD_REGISTRY_META_REGISTRY);

        return ((SymbolicObject) metaRegistry).callMethod(SymbolicNames.METHOD_REGISTRY_GET_BY_KEY, createResourceKey("biome"));
    }

	private void stopAllExecutors() throws IllegalArgumentException, IllegalAccessException {
		Class<?> clazz = utilClass.getClazz();
		for (Field field: clazz.getDeclaredFields()) {
			if ((field.getModifiers() & Modifier.STATIC) > 0 && field.getType().equals(ExecutorService.class)) {
				field.setAccessible(true);
				ExecutorService exec = (ExecutorService) field.get(null);
				exec.shutdownNow();
			}
		}
	}

	private Object createResourceKey(String key)
			throws InstantiationException, IllegalAccessException,
			IllegalArgumentException, InvocationTargetException, MinecraftInterfaceException {
		return resourceKeyClass.callConstructor(SymbolicNames.CONSTRUCTOR_RESOURCE_KEY, key).getObject();
	}

	private MethodHandle getMethodHandle(SymbolicClass symbolicClass, String method) throws IllegalAccessException {
	    Method rawMethod = symbolicClass.getMethod(method).getRawMethod();
	    MethodHandle mh = MethodHandles.lookup().unreflect(rawMethod);
	    return mh.asType(mh.type().erase());
	}

	private static Object callParameterlessMethodReturning(Object obj, Class<?> retClass)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, MinecraftInterfaceException {
		Method candidate = null;
		for (Method meth: obj.getClass().getMethods()) {
			if (((meth.getModifiers() & Modifier.STATIC) == 0)
			&& meth.getParameterCount() == 0
			&& retClass.isAssignableFrom(meth.getReturnType())) {
				if (candidate == null) {
					candidate = meth;
				} else {
					throw new MinecraftInterfaceException("found multiple methods returning " + retClass.getCanonicalName()
						+ " on class " + obj.getClass().getCanonicalName());
				}
			}
		}
		if (candidate == null) {
			throw new MinecraftInterfaceException("couldn't find method returning " + retClass.getCanonicalName()
				+ " on class " + obj.getClass().getCanonicalName());
		}
		return candidate.invoke(obj);
	}

	private class World implements MinecraftInterface.World {
		/**
		 * A ThreadLocal instance containing a BiomeProvider
		 * instance of the current world for each thread, giving
		 * access to the quarter-scale biome data.
		 */
	    private final ThreadLocal<Object> threadedOWBiomeProvider;
	    private final ThreadLocal<Object> threadedNetherBiomeProvider;
	    /**
	     * The BiomeZoomer instance for the current world, which
	     * interpolates the quarter-scale BiomeProvider to give
	     * full-scale biome data.
	     */
	    private final Object biomeZoomer;
	    /**
	     * The seed used by the BiomeZoomer during interpolation.
	     * It is derived from the world seed.
	     */
		private final long seedForBiomeZoomer;

	    private World(ThreadLocal<Object> th_OWBProvider, ThreadLocal<Object> th_NetherBProvider, Object biomeZoomer, long seedForBiomeZoomer) {
	    	this.threadedOWBiomeProvider = Objects.requireNonNull(th_OWBProvider);
	    	this.threadedNetherBiomeProvider = Objects.requireNonNull(th_NetherBProvider);
	    	this.seedForBiomeZoomer = seedForBiomeZoomer;
	    	this.biomeZoomer = Objects.requireNonNull(biomeZoomer);
	    }

		@Override
		public<T> T getBiomeData(
				Dimension dimension,
				int x, int y, int width, int height,
				boolean useQuarterResolution, Function<int[], T> biomeDataMapper)
				throws MinecraftInterfaceException {
			ThreadLocal<Object> biomeProvider;
			int biomeHeight;

			switch (dimension) {
			case OVERWORLD:
				biomeProvider = this.threadedOWBiomeProvider;
				biomeHeight = 0; // The overworld uses y=0 for all heights
				break;
			case NETHER:
				biomeProvider = this.threadedNetherBiomeProvider;
				biomeHeight = 63; // Pick an arbitrary value
				break;
			default:
				throw new UnsupportedDimensionException(dimension);
			}

			int size = width * height;
		    return dataArray.withArrayFaillible(size, data -> {
			    try {
			    	if(size == 1) {
			    		data[0] = getBiomeIdAt(biomeProvider, biomeHeight, x, y, useQuarterResolution);
			    		return biomeDataMapper.apply(data);
			    	}

			        /**
			         * We break the region in 16x16 chunks, to get better performance out
			         * of the LazyArea used by the game. This gives a ~2x improvement.
		             */
		            int chunkSize = 16;
		            for (int x0 = 0; x0 < width; x0 += chunkSize) {
		                int w = Math.min(chunkSize, width - x0);

		                for (int y0 = 0; y0 < height; y0 += chunkSize) {
		                    int h = Math.min(chunkSize, height - y0);

		                    for (int i = 0; i < w; i++) {
		                        for (int j = 0; j < h; j++) {
		                            int trueIdx = (x0 + i) + (y0 + j) * width;
		                            data[trueIdx] = getBiomeIdAt(biomeProvider, biomeHeight, x + x0 + i, y + y0 + j, useQuarterResolution);
		                        }
		                    }
		                }
		            }
			    } catch (Throwable e) {
			        throw new MinecraftInterfaceException("unable to get biome data", e);
			    }

			    return biomeDataMapper.apply(data);
		    });
		}

		@Override
		public Set<Dimension> supportedDimensions() {
			return SUPPORTED_DIMENSIONS;
		}

		private int getBiomeIdAt(ThreadLocal<Object> threadedBiomeProvider, int biomeHeight, int x, int y, boolean useQuarterResolution) throws Throwable {
			Object biome;
			if(useQuarterResolution) {
				biome = biomeProviderGetBiomeMethod.invokeExact(threadedBiomeProvider.get(), x, biomeHeight, y);
			} else {
				biome = biomeZoomerGetBiomeMethod.invokeExact(biomeZoomer, seedForBiomeZoomer, x, biomeHeight, y, threadedBiomeProvider.get());
			}
			return (int) registryGetIdMethod.invokeExact(biomeRegistry, biome);
		}
	}
}

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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import amidst.clazz.symbolic.SymbolicClass;
import amidst.clazz.symbolic.SymbolicObject;
import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.minecraftinterface.RecognisedVersion;
import amidst.mojangapi.world.WorldType;
import amidst.util.ArrayCache;

public class LocalMinecraftInterface implements MinecraftInterface {

    private boolean isInitialized = false;
	private final RecognisedVersion recognisedVersion;

	private final SymbolicClass registryClass;
	private final SymbolicClass registryKeyClass;
	private final SymbolicClass overworldBiomeProviderClass;
	private final SymbolicClass multiNoiseBiomeProviderClass;
	private final SymbolicClass noiseBiomeProviderClass;
	private final SymbolicClass biomeZoomerClass;
	private final SymbolicClass utilClass;

	private MethodHandle registryGetIdMethod;
    private MethodHandle biomeProviderGetBiomeMethod;
    private MethodHandle biomeZoomerGetBiomeMethod;

	private Object biomeRegistry;

    /**
     * An array used to return biome data
     */
    private final ArrayCache<int[]> dataArray = ArrayCache.makeIntArrayCache(256);

	public LocalMinecraftInterface(Map<String, SymbolicClass> symbolicClassMap, RecognisedVersion recognisedVersion) {
		this.recognisedVersion = recognisedVersion;
		this.registryClass = symbolicClassMap.get(SymbolicNames.CLASS_REGISTRY);
        this.registryKeyClass = symbolicClassMap.get(SymbolicNames.CLASS_REGISTRY_KEY);
        this.overworldBiomeProviderClass = symbolicClassMap.get(SymbolicNames.CLASS_OVERWORLD_BIOME_PROVIDER);
        this.multiNoiseBiomeProviderClass = symbolicClassMap.get(SymbolicNames.CLASS_MULTI_NOISE_BIOME_PROVIDER);
        this.noiseBiomeProviderClass = symbolicClassMap.get(SymbolicNames.CLASS_NOISE_BIOME_PROVIDER);
        this.biomeZoomerClass = symbolicClassMap.get(SymbolicNames.CLASS_BIOME_ZOOMER);
        this.utilClass = symbolicClassMap.get(SymbolicNames.CLASS_UTIL);
	}

	@Override
	public synchronized MinecraftInterface.World createWorld(long seed, WorldType worldType, String generatorOptions)
			throws MinecraftInterfaceException {
	    initializeIfNeeded();

	    try {
	        Object biomeProvider = createBiomeProviderObject(seed, worldType);
	        Object biomeZoomer = biomeZoomerClass.getClazz().getEnumConstants()[0];
            long seedForBiomeZoomer = makeSeedForBiomeZoomer(seed);
            return new World(biomeProvider, biomeZoomer, seedForBiomeZoomer);

        } catch(IllegalArgumentException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
            throw new MinecraftInterfaceException("unable to create world", e);
        }
	}

	private Object createBiomeProviderObject(long seed, WorldType worldType)
            throws IllegalArgumentException, IllegalAccessException, InstantiationException, InvocationTargetException {
	   	return overworldBiomeProviderClass.callConstructor(
		   			SymbolicNames.CONSTRUCTOR_OVERWORLD_BIOME_PROVIDER,
		   			seed,
		   			false,
		   			worldType.equals(WorldType.LARGE_BIOMES)
	   			).getObject();
	}
	
	@SuppressWarnings("unused")
	private Object createNetherBiomeProviderObject(long seed)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		
		return multiNoiseBiomeProviderClass.getMethod(SymbolicNames.METHOD_MULTI_NOISE_BIOME_PROVIDER_PRESET_NETHER).getRawMethod().invoke(null, seed); 
	}

	private synchronized void initializeIfNeeded() throws MinecraftInterfaceException {
	    if (isInitialized) {
	        return;
	    }

	    try {
	        Object metaRegistry = ((SymbolicObject) registryClass
	                .getStaticFieldValue(SymbolicNames.FIELD_REGISTRY_META_REGISTRY)).getObject();
	        
	        stopAllExecutors();

            biomeRegistry = Objects.requireNonNull(getFromRegistryByKey(metaRegistry, "biome"));

            registryGetIdMethod = getMethodHandle(registryClass, SymbolicNames.METHOD_REGISTRY_GET_ID);
            biomeProviderGetBiomeMethod = getMethodHandle(noiseBiomeProviderClass, SymbolicNames.METHOD_NOISE_BIOME_PROVIDER_GET_BIOME);
            biomeZoomerGetBiomeMethod = getMethodHandle(biomeZoomerClass, SymbolicNames.METHOD_BIOME_ZOOMER_GET_BIOME);
        } catch(IllegalArgumentException | IllegalAccessException | InstantiationException
                | InvocationTargetException e) {
            throw new MinecraftInterfaceException("unable to initialize the MinecraftInterface", e);
        }

	    isInitialized = true;
	}

	private Object getFromRegistryByKey(Object registry, String key)
	        throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
	    Object registryKey = registryKeyClass
                .callConstructor(SymbolicNames.CONSTRUCTOR_REGISTRY_KEY, key)
                .getObject();

	    Method getByKey = registryClass.getMethod(SymbolicNames.METHOD_REGISTRY_GET_BY_KEY).getRawMethod();
	    return getByKey.invoke(registry, registryKey);
	}
	
	private static long makeSeedForBiomeZoomer(long seed) throws MinecraftInterfaceException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
			buf.putLong(seed);
			byte[] bytes = digest.digest(buf.array());
			
			long result = 0;
			for (int i = 0; i < 8; i++) {
				result |= (bytes[i] & 0xffL) << (i * 8L);
			}
			return result;
		} catch (NoSuchAlgorithmException e) {
			throw new MinecraftInterfaceException("unable to hash seed", e);
		}
	}
	
	private void stopAllExecutors() throws IllegalArgumentException, IllegalAccessException {
		Class<?> clazz = utilClass.getClazz();
		for (Field field : clazz.getDeclaredFields()) {
			if ((field.getModifiers() & Modifier.STATIC) > 0 && field.getType().equals(ExecutorService.class)) {
				field.setAccessible(true);
				ExecutorService exec = (ExecutorService) field.get(null);
				exec.shutdownNow();
			}
		}
	}

	private MethodHandle getMethodHandle(SymbolicClass symbolicClass, String method) throws IllegalAccessException {
	    Method rawMethod = symbolicClass.getMethod(method).getRawMethod();
	    return MethodHandles.lookup().unreflect(rawMethod);
	}
	
	@Override
	public RecognisedVersion getRecognisedVersion() {
		return recognisedVersion;
	}

	private class World implements MinecraftInterface.World {
		/**
		 * A BiomeProvider instance for the current world, giving
		 * access to the quarter-scale biome data.
		 */
	    private Object biomeProvider;
	    /**
	     * The BiomeZoomer instance for the current world, which
	     * interpolates the quarter-scale BiomeProvider to give
	     * full-scale biome data.
	     */
	    private Object biomeZoomer;
	    /**
	     * The seed used by the BiomeZoomer during interpolation.
	     * It is derived from the world seed.
	     */
		private long seedForBiomeZoomer;

	    private World(Object biomeProvider, Object biomeZoomer, long seedForBiomeZoomer) {
	    	this.biomeProvider = biomeProvider;
	    	this.biomeZoomer = biomeZoomer;
	    	this.seedForBiomeZoomer = seedForBiomeZoomer;
	    }

		@Override
		public<T> T getBiomeData(int x, int y, int width, int height,
				boolean useQuarterResolution, Function<int[], T> biomeDataMapper)
				throws MinecraftInterfaceException {

			int size = width * height;
		    return dataArray.withArrayFaillible(size, data -> {
			    try {
			    	if(size == 1) {
			    		data[0] = getBiomeIdAt(x, y, useQuarterResolution);
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
		                            data[trueIdx] = getBiomeIdAt(x + x0 + i, y + y0 + j, useQuarterResolution);
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

		private int getBiomeIdAt(int x, int y, boolean useQuarterResolution) throws Throwable {
		    Object biome;
	        // I think this has to be 0 if we want to use the non-columed biome zoomer.
		    int height = 0;
		    if(useQuarterResolution) {
		        biome = biomeProviderGetBiomeMethod.invoke(biomeProvider, x, height, y);
		    } else {
		        biome = biomeZoomerGetBiomeMethod.invoke(biomeZoomer, seedForBiomeZoomer, x, height, y, biomeProvider);
		    }
		    return (int) registryGetIdMethod.invoke(biomeRegistry, biome);
		}
	}
}

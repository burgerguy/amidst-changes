package amidst.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import amidst.fragment.constructor.FragmentConstructor;
import amidst.fragment.drawer.FragmentDrawer;
import amidst.fragment.loader.FragmentLoader;
import amidst.map.layer.Layer;
import amidst.map.layer.LayerType;

public class LayerContainer {
	private final List<AtomicBoolean> invalidatedLayers;
	private final List<LayerDeclaration> declarations;
	private final List<FragmentConstructor> constructors;
	private final List<FragmentLoader> loaders;
	private final List<FragmentDrawer> drawers;

	public LayerContainer(Layer... layers) {
		List<AtomicBoolean> invalidatedLayers = new ArrayList<AtomicBoolean>(
				layers.length);
		List<LayerDeclaration> declarations = new ArrayList<LayerDeclaration>(
				layers.length);
		List<FragmentConstructor> constructor = new ArrayList<FragmentConstructor>(
				layers.length);
		List<FragmentLoader> loader = new ArrayList<FragmentLoader>(
				layers.length);
		List<FragmentDrawer> drawer = new ArrayList<FragmentDrawer>(
				layers.length);
		for (Layer layer : layers) {
			invalidatedLayers.add(new AtomicBoolean(false));
			declarations.add(layer.getLayerDeclaration());
			constructor.add(layer.getFragmentConstructor());
			loader.add(layer.getFragmentLoader());
			drawer.add(layer.getFragmentDrawer());
		}
		this.invalidatedLayers = Collections
				.unmodifiableList(invalidatedLayers);
		this.declarations = Collections.unmodifiableList(declarations);
		this.constructors = Collections.unmodifiableList(constructor);
		this.loaders = Collections.unmodifiableList(loader);
		this.drawers = Collections.unmodifiableList(drawer);
	}

	public List<LayerDeclaration> getLayerDeclarations() {
		return declarations;
	}

	public List<FragmentDrawer> getFragmentDrawers() {
		return drawers;
	}

	public void clearInvalidatedLayers() {
		for (AtomicBoolean invalidatedLayer : invalidatedLayers) {
			invalidatedLayer.set(false);
		}
	}

	public void invalidateLayer(LayerType layerType) {
		invalidatedLayers.get(layerType.ordinal()).set(true);
	}

	public void constructAll(Fragment fragment) {
		for (FragmentConstructor constructor : constructors) {
			constructor.construct(fragment);
		}
	}

	public void loadAll(Fragment fragment) {
		for (FragmentLoader loader : loaders) {
			loader.load(fragment);
		}
	}

	public void reloadInvalidated(Fragment fragment) {
		for (int i = 0; i < loaders.size(); i++) {
			if (invalidatedLayers.get(i).get()) {
				loaders.get(i).reload(fragment);
			}
		}
	}
}

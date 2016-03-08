package org.bimserver.gltf;

import java.util.Set;

import org.bimserver.emf.Schema;
import org.bimserver.interfaces.objects.SPluginType;
import org.bimserver.models.store.ObjectDefinition;
import org.bimserver.plugins.PluginConfiguration;
import org.bimserver.plugins.PluginContext;
import org.bimserver.plugins.serializers.Serializer;
import org.bimserver.plugins.serializers.SerializerPlugin;
import org.bimserver.shared.exceptions.PluginException;

public class BinaryGltfSerializerPlugin implements SerializerPlugin {

	@Override
	public void init(PluginContext pluginContext) throws PluginException {
		
	}

	@Override
	public ObjectDefinition getSettingsDefinition() {
		return null;
	}

	@Override
	public SPluginType getPluginType() {
		return null;
	}

	@Override
	public Serializer createSerializer(PluginConfiguration plugin) {
		return null;
	}

	@Override
	public Set<Schema> getSupportedSchemas() {
		return null;
	}
}
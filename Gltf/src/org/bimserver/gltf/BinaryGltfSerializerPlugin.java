package org.bimserver.gltf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.bimserver.emf.Schema;
import org.bimserver.models.store.ObjectDefinition;
import org.bimserver.plugins.PluginConfiguration;
import org.bimserver.plugins.PluginContext;
import org.bimserver.plugins.serializers.Serializer;
import org.bimserver.plugins.serializers.SerializerPlugin;
import org.bimserver.shared.exceptions.PluginException;

public class BinaryGltfSerializerPlugin implements SerializerPlugin {

	private byte[] fragmentShaderBytes;
	private byte[] vertexShaderBytes;

	@Override
	public void init(PluginContext pluginContext) throws PluginException {
		Path fragmentShaderPath = pluginContext.getRootPath().resolve("shaders/fragment.shader");
		Path vertexShaderPath = pluginContext.getRootPath().resolve("shaders/vertex.shader");
		
		try {
			fragmentShaderBytes = Files.readAllBytes(fragmentShaderPath);
			vertexShaderBytes = Files.readAllBytes(vertexShaderPath);
		} catch (IOException e) {
			throw new PluginException(e);
		}
	}

	@Override
	public ObjectDefinition getSettingsDefinition() {
		return null;
	}

	@Override
	public Serializer createSerializer(PluginConfiguration plugin) {
		return new BinaryGltfSerializer(fragmentShaderBytes, vertexShaderBytes);
	}

	@Override
	public Set<Schema> getSupportedSchemas() {
		return Collections.singleton(Schema.IFC2X3TC1);
	}

	@Override
	public boolean needsGeometry() {
		return true;
	}
}